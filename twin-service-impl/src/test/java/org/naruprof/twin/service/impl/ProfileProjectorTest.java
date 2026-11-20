package org.naruprof.twin.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;
import org.naruprof.twin.service.api.SummaryResult;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.naruprof.twin.service.impl.TwinFixture.P;

class ProfileProjectorTest {

    final TwinFixture f = new TwinFixture();
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    ProfileProjector projector;

    ProfileProjector start(int queueCapacity, LearningSource src) {
        ProjectionSettings s = new ProjectionSettings("writer-a", 5, queueCapacity, Duration.ofMillis(20),
                Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofSeconds(2));
        projector = new ProfileProjector(P, src, f.book, f.book, f.projection,
                new ReconcileService(src, f.book, f.projection, f.provisioning, f.deletion, f.diag), f.deletion, f.diag, s, scheduler);
        projector.start();
        return projector;
    }

    @AfterEach
    void stop() {
        if (projector != null) {
            projector.close();
        }
        scheduler.shutdownNow();
    }

    static void await(BooleanSupplier c) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(10);
        }
    }

    U64 applied() {
        return f.book.rows.containsKey(P) ? f.book.rows.get(P).applied().orElse(null) : null;
    }

    @Test
    void bootstrapsFromSnapshotThenWatchesFromConfirmedRevision() throws Exception {
        f.source.commit(1, 0, 0, 0);
        start(16, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        assertThat(f.source.watchCursors).containsExactly(U64.of(1));
        f.source.commit(1, 1, 0, 0);
        f.source.commit(1, 1, 2, 1);
        await(() -> U64.of(3).equals(applied()));
        assertThat(f.reads.read(P.value())).isInstanceOf(SummaryResult.Found.class);
    }

    @Test
    void droppedStreamResumesFromLastConfirmedCursorWithoutSnapshot() throws Exception {
        start(16, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        f.source.commit(1, 0, 0, 0);
        await(() -> U64.of(1).equals(applied()));
        int snapshots = f.source.snapshots;
        f.source.fail(new SourceException(SourceException.Kind.TRANSIENT, "reset"));
        f.source.listeners.clear();
        f.source.commit(1, 1, 0, 0); // committed while disconnected
        await(() -> U64.of(2).equals(applied()));
        assertThat(f.source.watchCursors).endsWith(U64.of(1));
        assertThat(f.source.snapshots).isEqualTo(snapshots);
    }

    @Test
    void updateCommittedExactlyBetweenSnapshotAndSubscribeIsReplayed() throws Exception {
        LearningSource racing = new LearningSource() {
            boolean once = true;

            @Override
            public SourceState getSnapshot(ProfileId p) {
                SourceState s = f.source.getSnapshot(p);
                if (once) {
                    once = false;
                    f.source.commit(1, 0, 0, 0); // lands after the snapshot, before Watch
                }
                return s;
            }

            @Override
            public Subscription watch(ProfileId p, U64 after, int window, Listener l) {
                return f.source.watch(p, after, window, l);
            }
        };
        start(16, racing);
        await(() -> U64.of(1).equals(applied()));
        assertThat(f.source.watchCursors).containsExactly(U64.ZERO);
    }

    @Test
    void unexpectedGapTriggersFreshSnapshotAndRestart() throws Exception {
        start(16, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        int snapshots = f.source.snapshots;
        f.source.log.add(new SourceState.Live(P, U64.of(1), LearningSummary.of(1, 0, 0, 0)));
        f.source.log.add(new SourceState.Live(P, U64.of(2), LearningSummary.of(1, 1, 0, 0)));
        f.source.push(f.source.log.get(2)); // 1 was never delivered
        await(() -> U64.of(2).equals(applied()));
        assertThat(f.source.snapshots).isGreaterThan(snapshots);
        assertThat(f.diag.counter("diagnostic.unexpected_gap")).isEqualTo(1);
    }

    @Test
    void outOfRangeCursorTakesSnapshot() throws Exception {
        start(16, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        int snapshots = f.source.snapshots;
        f.source.fail(new SourceException(SourceException.Kind.OUT_OF_RANGE, "expired"));
        await(() -> f.source.snapshots > snapshots && projector.state() == ProfileProjector.ConsumerState.STREAMING);
    }

    @Test
    void largeBacklogIsConsumedUnderFlowControlWithSmallQueue() throws Exception {
        for (int i = 1; i <= 50; i++) {
            f.source.commit(1, i, 0, 0);
        }
        f.source.rewindTo(0); // snapshot at 0, then a 50-revision backlog to replay
        start(2, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        for (int i = 1; i <= 50; i++) {
            f.source.commit(1, i, 0, 0);
        }
        await(() -> U64.of(50).equals(applied()));
        assertThat(f.diag.counter("source.saturated")).isZero();
    }

    @Test
    void saturatedStreamIsDroppedAndResumedFromCursor() throws Exception {
        f.source.ignoreFlowControl = true; // producer ignores demand
        start(1, f.source);
        await(() -> projector.state() == ProfileProjector.ConsumerState.STREAMING);
        f.ditto.gate = new java.util.concurrent.CountDownLatch(1); // the single worker blocks on its first apply
        for (int i = 0; i < 20; i++) {
            f.source.commit(1, i, 0, 0); // pushed regardless of demand
        }
        await(() -> f.diag.counter("source.saturated") > 0);
        java.util.concurrent.CountDownLatch g = f.ditto.gate;
        f.ditto.gate = null;
        g.countDown();
        await(() -> U64.of(20).equals(applied()));
        assertThat(f.ditto.props(P)).containsEntry("sequence", "20");
        assertThat(f.diag.counter("source.saturated")).isPositive();
    }

    @Test
    void dittoOutageKeepsLearningAvailableThenCatchesUp() throws Exception {
        start(16, f.source);
        await(() -> U64.ZERO.equals(applied()));
        f.ditto.down = true;
        f.source.commit(1, 0, 0, 0); // NARU keeps accepting lessons
        await(() -> projector.state() == ProfileProjector.ConsumerState.DITTO_UNAVAILABLE);
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Unavailable("ditto_unavailable"));
        f.ditto.down = false;
        await(() -> U64.of(1).equals(applied()));
        assertThat(f.reads.read(P.value())).isInstanceOf(SummaryResult.Found.class);
    }

    @Test
    void tombstoneOnTheStreamRetiresAndLateUpdatesCannotResurrect() throws Exception {
        start(16, f.source);
        f.source.commit(1, 0, 0, 0);
        await(() -> U64.of(1).equals(applied()));
        f.source.delete();
        await(() -> f.book.rows.get(P).status() == CheckpointStatus.DELETED);
        assertThat(projector.state()).isEqualTo(ProfileProjector.ConsumerState.RETIRED);
        f.source.push(new SourceState.Live(P, U64.of(1), LearningSummary.of(1, 0, 0, 0))); // delayed update
        Thread.sleep(100);
        assertThat(f.ditto.things).isEmpty();
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Missing());
    }

    @Test
    void unknownProfileAtSourceIsNotTreatedAsDeletion() throws Exception {
        start(16, f.source);
        f.source.commit(1, 0, 0, 0);
        await(() -> U64.of(1).equals(applied()));
        f.source.snapshotFailure = new SourceException(SourceException.Kind.NOT_FOUND, "unknown");
        f.source.fail(new SourceException(SourceException.Kind.NOT_FOUND, "unknown"));
        await(() -> projector.state() == ProfileProjector.ConsumerState.SOURCE_UNKNOWN_PROFILE);
        assertThat(f.ditto.things).isNotEmpty();
        assertThat(f.reads.read(P.value())).isInstanceOf(SummaryResult.Found.class);
    }
}
