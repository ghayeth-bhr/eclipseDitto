package org.naruprof.twin.fixture.naru;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.naruprof.contract.learning.v1.Deleted;
import org.naruprof.contract.learning.v1.LearningProjectionGrpc;
import org.naruprof.contract.learning.v1.Profile;
import org.naruprof.contract.learning.v1.ProjectionState;
import org.naruprof.contract.learning.v1.Summary;
import org.naruprof.contract.learning.v1.WatchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Deterministic in-memory NARU LearningProjection publisher implementing the contract semantics:
 * per-profile gap-free revision log, consistent snapshot, Watch(after) = exclusive replay then
 * tail, OUT_OF_RANGE for expired/ahead cursors, NOT_FOUND for unknown profiles, retained
 * tombstones. Fault-injection hooks let tests break streams, rewind the source (disaster restore),
 * inject raw messages and commit "exactly between snapshot and subscribe".
 */
public class FakeLearningProjection extends LearningProjectionGrpc.LearningProjectionImplBase {

    private static final Logger log = LoggerFactory.getLogger(FakeLearningProjection.class);

    private final Object lock = new Object();
    private final Map<String, ProfileLog> profiles = new ConcurrentHashMap<>();
    private final Set<Watcher> watchers = new CopyOnWriteArraySet<>();
    private volatile boolean available = true;
    private volatile Consumer<String> afterSnapshotHook = p -> { };
    private final AtomicInteger snapshotCalls = new AtomicInteger();
    private final AtomicInteger watchCalls = new AtomicInteger();
    private final List<Long> watchCursors = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final class ProfileLog {
        final List<ProjectionState> revisions = new ArrayList<>(); // index == sequence
        long retainedFloor = 0; // cursors below this are expired
    }

    private record Watcher(String profileId, ServerCallStreamObserver<ProjectionState> out) {
    }

    // ------------------------------------------------------------------ fixture control

    /** Creates a profile at revision 0 with four zero counts. */
    public void createProfile(String profileId) {
        synchronized (lock) {
            ProfileLog l = new ProfileLog();
            l.revisions.add(state(profileId, 0, Summary.getDefaultInstance()));
            profiles.put(profileId, l);
        }
    }

    /** Commits a full replacement summary; returns its sequence. */
    public long commit(String profileId, long teaching, long practice, long propositions, long chains) {
        return append(profileId, seq -> state(profileId, seq, Summary.newBuilder().setTeachingSessions(teaching)
                .setPracticeSessions(practice).setPropositions(propositions).setReasoningChains(chains).build()));
    }

    /** Commits the profile deletion (retained tombstone). */
    public long commitDeletion(String profileId) {
        return append(profileId, seq -> ProjectionState.newBuilder().setProfileId(profileId).setSequence(seq)
                .setDeleted(Deleted.getDefaultInstance()).build());
    }

    private long append(String profileId, java.util.function.LongFunction<ProjectionState> f) {
        synchronized (lock) {
            ProfileLog l = log(profileId);
            ProjectionState last = l.revisions.get(l.revisions.size() - 1);
            if (last.hasDeleted()) {
                throw new IllegalStateException("profile retired");
            }
            long seq = l.revisions.size();
            ProjectionState s = f.apply(seq);
            l.revisions.add(s);
            for (Watcher w : watchers) {
                if (w.profileId.equals(profileId)) {
                    safeSend(w, s);
                }
            }
            return seq;
        }
    }

    /** Simulates a disaster restore of NARU to an older revision (source moves backwards). */
    public void rewindTo(String profileId, long sequence) {
        synchronized (lock) {
            ProfileLog l = log(profileId);
            while (l.revisions.size() > sequence + 1) {
                l.revisions.remove(l.revisions.size() - 1);
            }
            breakAllWatchers(Status.UNAVAILABLE.withDescription("restored"));
        }
    }

    /** Expire history: cursors below {@code floor} get OUT_OF_RANGE. */
    public void expireHistoryBelow(String profileId, long floor) {
        synchronized (lock) {
            log(profileId).retainedFloor = floor;
        }
    }

    /** Pushes an arbitrary message to current watchers (late replay, drift, malformed...). */
    public void injectToWatchers(ProjectionState raw) {
        synchronized (lock) {
            for (Watcher w : watchers) {
                safeSend(w, raw);
            }
        }
    }

    public void breakAllWatchers(Status status) {
        synchronized (lock) {
            for (Watcher w : watchers) {
                try {
                    w.out.onError(status.asRuntimeException());
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
            watchers.clear();
        }
    }

    public void setAvailable(boolean available) {
        this.available = available;
        if (!available) {
            breakAllWatchers(Status.UNAVAILABLE.withDescription("naru down"));
        }
    }

    /** Runs after each GetSnapshot response is computed and sent, before the client can subscribe. */
    public void setAfterSnapshotHook(Consumer<String> hook) {
        this.afterSnapshotHook = hook == null ? p -> { } : hook;
    }

    public long head(String profileId) {
        synchronized (lock) {
            return log(profileId).revisions.size() - 1L;
        }
    }

    public int snapshotCalls() {
        return snapshotCalls.get();
    }

    public int watchCalls() {
        return watchCalls.get();
    }

    public List<Long> watchCursors() {
        return List.copyOf(watchCursors);
    }

    public int activeWatchers() {
        return watchers.size();
    }

    public static ProjectionState state(String profileId, long seq, Summary s) {
        return ProjectionState.newBuilder().setProfileId(profileId).setSequence(seq).setSummary(s).build();
    }

    public static ProjectionState state(String profileId, long seq, long t, long p, long props, long chains) {
        return state(profileId, seq, Summary.newBuilder().setTeachingSessions(t).setPracticeSessions(p)
                .setPropositions(props).setReasoningChains(chains).build());
    }

    private ProfileLog log(String profileId) {
        ProfileLog l = profiles.get(profileId);
        if (l == null) {
            throw new IllegalArgumentException("unknown fixture profile");
        }
        return l;
    }

    private void safeSend(Watcher w, ProjectionState s) {
        try {
            if (!w.out.isCancelled()) {
                w.out.onNext(s);
            }
        } catch (RuntimeException e) {
            watchers.remove(w);
        }
    }

    // ------------------------------------------------------------------ gRPC

    @Override
    public void getSnapshot(Profile request, StreamObserver<ProjectionState> out) {
        snapshotCalls.incrementAndGet();
        if (!available) {
            out.onError(Status.UNAVAILABLE.withDescription("naru down").asRuntimeException());
            return;
        }
        ProjectionState head;
        synchronized (lock) {
            ProfileLog l = profiles.get(request.getProfileId());
            if (l == null) {
                out.onError(Status.NOT_FOUND.withDescription("unknown profile").asRuntimeException());
                return;
            }
            head = l.revisions.get(l.revisions.size() - 1);
        }
        out.onNext(head);
        out.onCompleted();
        afterSnapshotHook.accept(request.getProfileId());
    }

    @Override
    public void watch(WatchRequest request, StreamObserver<ProjectionState> rawOut) {
        watchCalls.incrementAndGet();
        watchCursors.add(request.getAfterSequence());
        ServerCallStreamObserver<ProjectionState> out = (ServerCallStreamObserver<ProjectionState>) rawOut;
        if (!available) {
            out.onError(Status.UNAVAILABLE.withDescription("naru down").asRuntimeException());
            return;
        }
        synchronized (lock) {
            ProfileLog l = profiles.get(request.getProfileId());
            if (l == null) {
                out.onError(Status.NOT_FOUND.withDescription("unknown profile").asRuntimeException());
                return;
            }
            long after = request.getAfterSequence();
            long headSeq = l.revisions.size() - 1L;
            if (Long.compareUnsigned(after, headSeq) > 0 || Long.compareUnsigned(after, l.retainedFloor) < 0) {
                out.onError(Status.OUT_OF_RANGE.withDescription("cursor expired or ahead of head").asRuntimeException());
                return;
            }
            // subscribe and backfill atomically under the commit lock: no gap
            for (long s = after + 1; s <= headSeq; s++) {
                out.onNext(l.revisions.get((int) s));
            }
            Watcher w = new Watcher(request.getProfileId(), out);
            watchers.add(w);
            out.setOnCancelHandler(() -> watchers.remove(w));
        }
        log.debug("fixture watch after={} attached", request.getAfterSequence());
    }
}
