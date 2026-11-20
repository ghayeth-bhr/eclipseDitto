package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.CorruptCheckpointException;
import org.naruprof.twin.dal.api.FencedOutException;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, fenced, single-writer consumer for one profile.
 *
 * <p>All decisions and native writes run on ONE worker thread whose queue is bounded; stream
 * callbacks only enqueue. A full queue means the stream is saturated: it is cancelled and resumed
 * from the last confirmed cursor. A transient drop also resumes from the cursor. A gap,
 * OUT_OF_RANGE, drift or corrupt checkpoint triggers a fresh snapshot (full cycle) before the
 * watch restarts. Across processes, the SQL lease's fencing token makes writes single-writer.
 */
public class ProfileProjector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProfileProjector.class);

    public enum ConsumerState {
        STARTING, WAITING_FOR_LEASE, BOOTSTRAPPING, STREAMING, BACKOFF, SOURCE_UNAVAILABLE, SOURCE_AUTH_FAILED,
        SOURCE_UNKNOWN_PROFILE, SOURCE_INCOMPATIBLE, DITTO_UNAVAILABLE, DITTO_THROTTLED, DITTO_AUTH_FAILED,
        TWIN_CONFLICT, RESTORE_CONFLICT, RETIRED, STOPPED
    }

    private final ProfileId profile;
    private final LearningSource source;
    private final LeaseStore leases;
    private final CheckpointStore checkpoints;
    private final ProjectionService projection;
    private final ReconcileService reconcile;
    private final DeletionLifecycle deletion;
    private final ProjectionDiagnostics diag;
    private final ProjectionSettings settings;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor worker;
    private final String owner;
    private final Backoff backoff;

    private final AtomicLong generation = new AtomicLong();
    /** Generation of a saturated stream whose already-queued, in-order prefix may still be applied. */
    private volatile long drainGeneration = -1;
    private volatile ConsumerState state = ConsumerState.STARTING;
    private volatile Lease lease;
    private volatile LearningSource.Subscription subscription;
    private volatile U64 cursor; // last confirmed revision the stream continues from
    private volatile boolean stopped;
    private ScheduledFuture<?> pendingCycle;
    private ScheduledFuture<?> renewTask;
    private ScheduledFuture<?> reconcileTask;

    public ProfileProjector(ProfileId profile, LearningSource source, LeaseStore leases, CheckpointStore checkpoints,
                            ProjectionService projection, ReconcileService reconcile, DeletionLifecycle deletion,
                            ProjectionDiagnostics diag, ProjectionSettings settings, ScheduledExecutorService scheduler) {
        this.profile = profile;
        this.source = source;
        this.leases = leases;
        this.checkpoints = checkpoints;
        this.projection = projection;
        this.reconcile = reconcile;
        this.deletion = deletion;
        this.diag = diag;
        this.settings = settings;
        this.scheduler = scheduler;
        this.owner = settings.writerId() + "/" + UUID.randomUUID();
        this.backoff = new Backoff(settings.backoffBase(), settings.backoffCap());
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()),
                r -> {
                    Thread t = new Thread(r, "twin-projector-" + profile.value().substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });
    }

    public void start() {
        schedule(Cycle.FULL, Duration.ZERO);
        renewTask = scheduler.scheduleWithFixedDelay(this::renewLease,
                settings.leaseTtl().toMillis() / 3, settings.leaseTtl().toMillis() / 3, TimeUnit.MILLISECONDS);
        reconcileTask = scheduler.scheduleWithFixedDelay(() -> schedule(Cycle.PERIODIC_RECONCILE, Duration.ZERO),
                settings.reconcileInterval().toMillis(), settings.reconcileInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public ConsumerState state() {
        return state;
    }

    public Optional<Lease> lease() {
        return Optional.ofNullable(lease);
    }

    // ------------------------------------------------------------------ cycles

    private enum Cycle { FULL, RESUME, PERIODIC_RECONCILE }

    private synchronized void schedule(Cycle cycle, Duration delay) {
        if (stopped) {
            return;
        }
        if (cycle != Cycle.PERIODIC_RECONCILE && pendingCycle != null && !pendingCycle.isDone()) {
            pendingCycle.cancel(false);
        }
        ScheduledFuture<?> f = scheduler.schedule(() -> submit(cycle), delay.toMillis(), TimeUnit.MILLISECONDS);
        if (cycle != Cycle.PERIODIC_RECONCILE) {
            pendingCycle = f;
        }
    }

    private void submit(Cycle cycle) {
        try {
            worker.execute(() -> runCycle(cycle));
        } catch (RejectedExecutionException full) {
            if (!stopped) {
                schedule(cycle, Duration.ofMillis(200)); // queue still draining
            }
        }
    }

    private void runCycle(Cycle cycle) {
        if (stopped) {
            return;
        }
        String op = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("profile", profile.value());
        MDC.put("op", op);
        try {
            if (!ensureLease()) {
                return;
            }
            if (cycle == Cycle.PERIODIC_RECONCILE) {
                if (state == ConsumerState.STREAMING || state == ConsumerState.RETIRED
                        || state == ConsumerState.RESTORE_CONFLICT) {
                    periodicReconcile();
                }
                return;
            }
            if (cycle == Cycle.RESUME && cursor != null) {
                Optional<Checkpoint> cp = checkpoints.find(profile);
                if (cp.isPresent() && cp.get().status() == CheckpointStatus.LIVE && cp.get().applied().isPresent()) {
                    cursor = cp.get().applied().get();
                    openWatch(cursor);
                    return;
                }
            }
            fullCycle();
        } catch (RuntimeException e) {
            handleFailure(e);
        } finally {
            MDC.remove("op");
        }
    }

    private void fullCycle() {
        closeWatch();
        state = ConsumerState.BOOTSTRAPPING;
        Optional<Checkpoint> cp = reconcile.loadCheckpoint(profile, lease);
        if (cp.isPresent() && cp.get().status() == CheckpointStatus.PENDING_DELETION) {
            log.info("resuming unfinished deletion profile={}", profile);
            deletion.resume(profile, lease);
        }
        ReconcileResult r = reconcile.reconcile(profile, lease, false);
        afterReconcile(r);
    }

    private void afterReconcile(ReconcileResult r) {
        switch (r.kind()) {
            case DELETED -> {
                closeWatch();
                state = ConsumerState.RETIRED;
                diag.clearFailure();
            }
            case RESTORE_CONFLICT -> {
                closeWatch();
                state = ConsumerState.RESTORE_CONFLICT;
                diag.failure("restore_conflict");
            }
            case LIVE, RESTORED -> {
                backoff.reset();
                diag.clearFailure();
                cursor = r.appliedSequence();
                openWatch(cursor);
            }
        }
    }

    private void periodicReconcile() {
        ReconcileResult r = reconcile.reconcile(profile, lease, false);
        if (r.kind() == ReconcileResult.Kind.LIVE && state == ConsumerState.STREAMING) {
            if (cursor == null || r.appliedSequence().isGreaterThan(cursor)) {
                cursor = r.appliedSequence();
            }
            return; // stream keeps running; replays below the cursor are ignored
        }
        afterReconcile(r);
    }

    private void openWatch(U64 after) {
        closeWatch();
        long gen = generation.incrementAndGet();
        diag.count("source.watch");
        log.info("watch profile={} after_sequence={}", profile, after);
        subscription = source.watch(profile, after, window(), new LearningSource.Listener() {
            @Override
            public void onState(SourceState s) {
                enqueue(gen, () -> {
                    try {
                        applyStreamed(gen, s);
                    } finally {
                        requestMore(gen);
                    }
                });
            }

            @Override
            public void onError(SourceException error) {
                enqueue(gen, () -> {
                    if (gen == generation.get()) {
                        handleFailure(error);
                    }
                });
            }

            @Override
            public void onCompleted() {
                enqueue(gen, () -> {
                    if (gen == generation.get()) {
                        diag.count("source.watch_completed");
                        schedule(Cycle.RESUME, backoff.next());
                    }
                });
            }
        });
        state = ConsumerState.STREAMING;
    }

    /** Flow-control window: leave queue room for control tasks (cycles, reconciles). */
    private int window() {
        return Math.max(1, settings.queueCapacity() / 2);
    }

    private void requestMore(long gen) {
        LearningSource.Subscription s = subscription;
        if (s != null && gen == generation.get()) {
            s.request(1);
        }
    }

    private void enqueue(long gen, Runnable task) {
        if (gen != generation.get() || stopped) {
            return;
        }
        try {
            worker.execute(() -> {
                MDC.put("profile", profile.value());
                try {
                    task.run();
                } catch (RuntimeException e) {
                    handleFailure(e);
                }
            });
        } catch (RejectedExecutionException saturated) {
            // bounded consumer is full: drop the stream, resume from the confirmed cursor
            if (generation.compareAndSet(gen, gen + 1)) {
                drainGeneration = gen; // keep the in-order prefix already queued
                diag.count("source.saturated");
                diag.diagnostic("stream_saturated", profile.value(), "queue capacity " + settings.queueCapacity());
                closeWatch();
                schedule(Cycle.RESUME, settings.backoffBase());
            }
        }
    }

    private void applyStreamed(long gen, SourceState s) {
        if ((gen != generation.get() && gen != drainGeneration) || stopped) {
            return; // superseded stream; resume will replay
        }
        diag.sourceSequence(s.sequence());
        if (s instanceof SourceState.Tombstone t) {
            log.info("tombstone received profile={} sequence={}", profile, t.sequence());
            closeWatch();
            deletion.begin(t, lease);
            state = ConsumerState.RETIRED;
            return;
        }
        SourceState.Live live = (SourceState.Live) s;
        U64 current = cursor;
        if (current != null && !live.sequence().isGreaterThan(current)) {
            diag.count("event.replay_ignored");
            if (live.sequence().equals(current)) {
                // equal sequence: confirm content equality (drift detection) without writing
                ApplyResult r = projection.applyLive(live, lease, ProjectionService.Mode.NORMAL);
                if (r == ApplyResult.DRIFT) {
                    schedule(Cycle.FULL, Duration.ZERO);
                    closeWatch();
                }
            }
            return;
        }
        if (current != null && live.sequence().isGreaterThan(current.next())) {
            diag.diagnostic("unexpected_gap", profile.value(), "cursor=" + current + " received=" + live.sequence());
            closeWatch();
            schedule(Cycle.FULL, Duration.ZERO);
            return;
        }
        long t0 = System.nanoTime();
        ApplyResult r = projection.applyLive(live, lease, ProjectionService.Mode.NORMAL);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        switch (r) {
            case APPLIED, ALREADY_APPLIED, REPAIRED -> {
                cursor = live.sequence();
                backoff.reset();
                diag.clearFailure();
                log.info("applied profile={} sequence={} result={} in {}ms lag={}", profile, live.sequence(), r, ms,
                        lag());
            }
            case IGNORED_STALE -> log.debug("stale replay ignored sequence={}", live.sequence());
            case DRIFT -> {
                closeWatch();
                schedule(Cycle.FULL, Duration.ZERO);
            }
            case RETIRED_IGNORED -> {
                closeWatch();
                state = ConsumerState.RETIRED;
            }
            case CONFLICT_PENDING -> {
                closeWatch();
                state = ConsumerState.RESTORE_CONFLICT;
            }
        }
    }

    private String lag() {
        U64 src = diag.lastSourceSequence();
        U64 c = cursor;
        if (src == null || c == null || !src.isGreaterThan(c)) {
            return "0";
        }
        return Long.toUnsignedString(src.bits() - c.bits());
    }

    // ------------------------------------------------------------------ failures

    private void handleFailure(Throwable e) {
        closeWatch();
        generation.incrementAndGet();
        Duration delay;
        if (e instanceof SourceException se) {
            switch (se.kind()) {
                case OUT_OF_RANGE -> {
                    diag.diagnostic("cursor_out_of_range", profile.value(), "taking a fresh snapshot");
                    state = ConsumerState.BOOTSTRAPPING;
                    schedule(Cycle.FULL, settings.backoffBase());
                    return;
                }
                case TRANSIENT -> {
                    state = ConsumerState.SOURCE_UNAVAILABLE;
                    delay = backoff.next();
                    diag.failure("source_unavailable");
                }
                case AUTH -> {
                    state = ConsumerState.SOURCE_AUTH_FAILED;
                    delay = settings.actionableFailureDelay();
                    diag.failure("source_auth");
                    log.error("NARU rejected the twin's credential; check TWIN_TO_NARU_TOKEN (retry in {})", delay);
                }
                case NOT_FOUND -> {
                    // unknown profile is NOT a deletion: keep the twin, investigate
                    state = ConsumerState.SOURCE_UNKNOWN_PROFILE;
                    delay = settings.actionableFailureDelay();
                    diag.failure("source_unknown_profile");
                    log.error("NARU does not know profile {}; twin kept unchanged pending investigation", profile);
                }
                case INCOMPATIBLE -> {
                    state = ConsumerState.SOURCE_INCOMPATIBLE;
                    delay = settings.actionableFailureDelay();
                    diag.failure("source_incompatible");
                    log.error("incompatible projection message: {}", se.getMessage());
                }
                default -> {
                    state = ConsumerState.BACKOFF;
                    delay = backoff.next();
                    diag.failure("source_other");
                }
            }
            scheduleAfterSource(delay);
            return;
        }
        if (e instanceof TwinStoreException te) {
            switch (te.kind()) {
                case UNAVAILABLE, UNKNOWN_OUTCOME -> {
                    state = ConsumerState.DITTO_UNAVAILABLE;
                    delay = te.retryAfter().orElseGet(backoff::next);
                    diag.failure("ditto_unavailable");
                }
                case THROTTLED -> {
                    state = ConsumerState.DITTO_THROTTLED;
                    delay = te.retryAfter().orElseGet(backoff::next);
                    diag.failure("ditto_throttled");
                }
                case UNAUTHENTICATED, FORBIDDEN -> {
                    state = ConsumerState.DITTO_AUTH_FAILED;
                    delay = settings.actionableFailureDelay();
                    diag.failure("ditto_auth");
                    log.error("Ditto denied a twin identity ({}); check subjects/policy (retry in {})", te.kind(), delay);
                }
                case CONFLICT -> {
                    state = ConsumerState.TWIN_CONFLICT;
                    delay = settings.actionableFailureDelay();
                    diag.failure("twin_conflict");
                    log.error("twin/policy conflict requires investigation: {}", te.getMessage());
                }
                default -> {
                    state = ConsumerState.BACKOFF;
                    delay = settings.actionableFailureDelay();
                    diag.failure("ditto_incompatible");
                }
            }
            log.warn("ditto failure class={} retry in {}ms", te.kind(), delay.toMillis());
            schedule(Cycle.FULL, delay);
            return;
        }
        if (e instanceof FencedOutException) {
            diag.failure("fenced_out");
            log.warn("lease lost for profile {}; another writer owns it", profile);
            lease = null;
            state = ConsumerState.WAITING_FOR_LEASE;
            schedule(Cycle.FULL, settings.leaseTtl());
            return;
        }
        if (e instanceof CorruptCheckpointException) {
            diag.failure("corrupt_checkpoint");
            schedule(Cycle.FULL, settings.backoffBase());
            return;
        }
        state = ConsumerState.BACKOFF;
        diag.failure("unexpected");
        log.error("unexpected projection failure", e);
        schedule(Cycle.FULL, backoff.next());
    }

    /** A source failure with confirmed state resumes from the cursor; otherwise re-bootstraps. */
    private void scheduleAfterSource(Duration delay) {
        log.warn("source failure state={} retry in {}ms (cursor {})", state, delay.toMillis(), cursor);
        schedule(cursor != null ? Cycle.RESUME : Cycle.FULL, delay);
    }

    // ------------------------------------------------------------------ lease

    private boolean ensureLease() {
        if (lease != null) {
            return true;
        }
        Optional<Lease> l = leases.tryAcquire(profile, owner, settings.leaseTtl());
        if (l.isEmpty()) {
            state = ConsumerState.WAITING_FOR_LEASE;
            schedule(Cycle.FULL, settings.leaseTtl().dividedBy(2));
            return false;
        }
        lease = l.get();
        log.info("projection lease acquired profile={} token={}", profile, lease.fencingToken());
        return true;
    }

    private void renewLease() {
        Lease l = lease;
        if (l == null || stopped) {
            return;
        }
        try {
            lease = leases.renew(l, settings.leaseTtl());
        } catch (FencedOutException e) {
            try {
                worker.execute(() -> handleFailure(e));
            } catch (RejectedExecutionException ignored) {
                lease = null;
            }
        } catch (RuntimeException e) {
            log.warn("lease renewal failed: {}", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ explicit operations

    /** Explicit operator reconciliation, executed on the single writer thread. */
    public ReconcileResult reconcileNow(boolean acceptSourceRestore, Duration timeout) throws Exception {
        Callable<ReconcileResult> c = () -> {
            if (!ensureLease()) {
                throw new FencedOutException("lease not available");
            }
            closeWatch();
            generation.incrementAndGet();
            ReconcileResult r = reconcile.reconcile(profile, lease, acceptSourceRestore);
            afterReconcile(r);
            return r;
        };
        Future<ReconcileResult> f = worker.submit(c);
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    // ------------------------------------------------------------------ shutdown

    private void closeWatch() {
        LearningSource.Subscription s = subscription;
        subscription = null;
        if (s != null) {
            s.close();
        }
    }

    /** Graceful drain: stop intake, let the in-flight apply finish, release the lease. */
    @Override
    public void close() {
        stopped = true;
        generation.incrementAndGet();
        synchronized (this) {
            if (pendingCycle != null) {
                pendingCycle.cancel(false);
            }
        }
        if (renewTask != null) {
            renewTask.cancel(false);
        }
        if (reconcileTask != null) {
            reconcileTask.cancel(false);
        }
        closeWatch();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(settings.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Lease l = lease;
        if (l != null) {
            try {
                leases.release(l);
            } catch (RuntimeException e) {
                log.debug("lease release failed on shutdown");
            }
        }
        state = ConsumerState.STOPPED;
        log.info("projector stopped profile={}", profile);
    }
}
