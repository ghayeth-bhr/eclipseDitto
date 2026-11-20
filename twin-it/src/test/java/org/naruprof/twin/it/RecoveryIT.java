package org.naruprof.twin.it;

import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.naruprof.twin.fixture.naru.FakeLearningProjection;
import org.naruprof.twin.fixture.naru.FixtureTraces;
import org.naruprof.twin.it.DittoItSupport.App;
import org.naruprof.twin.it.DittoItSupport.Naru;
import org.naruprof.twin.it.DittoItSupport.Resp;
import org.naruprof.twin.it.DittoItSupport.Who;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.naruprof.twin.it.DittoItSupport.await;
import static org.naruprof.twin.it.DittoItSupport.ditto;
import static org.naruprof.twin.it.DittoItSupport.http;
import static org.naruprof.twin.it.DittoItSupport.policyPath;
import static org.naruprof.twin.it.DittoItSupport.thingPath;

/** Acceptance-evidence rows that involve faults, restarts and rebuilds, against real Ditto. */
class RecoveryIT {

    String p;
    Naru naru;
    App app;
    Path dataDir;

    @BeforeAll
    static void reachable() {
        assumeTrue(DittoItSupport.dittoReachable(), "pinned Ditto stack not reachable at " + DittoItSupport.API);
    }

    @BeforeEach
    void up() throws Exception {
        p = DittoItSupport.newProfile();
        naru = new Naru();
        dataDir = DittoItSupport.tempDir();
        app = new App(p, dataDir, naru);
    }

    @AfterEach
    void down() throws Exception {
        app.close();
        naru.close();
        if (DittoItSupport.dittoReachable()) {
            DittoItSupport.purge(p);
        }
    }

    private void startAtThree() {
        FixtureTraces.commonTrace(naru.fake, p);
        app.start();
        await("revision 3", Duration.ofSeconds(30), () -> "3".equals(app.summarySequence()));
    }

    private String seq() {
        return app.summarySequence();
    }

    @Test
    void crashAfterNativePutBeforeSqlCommitAdvancesCheckpointOnceOnRestart() {
        startAtThree();
        app.stop();
        // NARU commits 4; the "crashed" writer had already PUT it natively but never committed SQL
        naru.fake.commit(p, 2, 1, 2, 1);
        Resp t = ditto(Who.PROJECTOR, "GET", thingPath(p));
        String body = "{\"policyId\":\"naruprof.learning:policy-" + p + "\",\"features\":{\"learning\":{\"properties\":"
                + "{\"sequence\":\"4\",\"teachingSessions\":\"2\",\"practiceSessions\":\"1\",\"propositions\":\"2\",\"reasoningChains\":\"1\"}}}}";
        assertThat(ditto(Who.PROJECTOR, "PUT", thingPath(p), body, Map.of("If-Match", t.etag())).status()).isEqualTo(204);
        String etagAfterCrash = ditto(Who.PROJECTOR, "GET", thingPath(p)).etag();

        app.start();
        await("checkpoint 4", Duration.ofSeconds(30), () -> "4".equals(app.status().json().path("appliedSequence").asText()));
        assertThat(seq()).isEqualTo("4");
        assertThat(ditto(Who.PROJECTOR, "GET", thingPath(p)).etag()).as("no second native write").isEqualTo(etagAfterCrash);
        assertThat(app.status().json().path("counters").path("event.already_applied").asLong()).isEqualTo(1);
    }

    @Test
    void streamBreakResumesFromExclusiveCursorIncludingCatchUp() {
        startAtThree();
        naru.fake.breakAllWatchers(Status.UNAVAILABLE.withDescription("reset"));
        naru.fake.commit(p, 2, 1, 2, 1); // committed while disconnected
        naru.fake.commit(p, 2, 2, 2, 1);
        await("revision 5", Duration.ofSeconds(20), () -> "5".equals(seq()));
        assertThat(naru.fake.watchCursors()).contains(3L);
        // break again while catching up a backlog
        for (int i = 0; i < 30; i++) {
            naru.fake.commit(p, 3 + i, 2, 2, 1);
        }
        naru.fake.breakAllWatchers(Status.UNAVAILABLE.withDescription("reset during catch-up"));
        await("revision 35", Duration.ofSeconds(30), () -> "35".equals(seq()));
    }

    @Test
    void updateExactlyBetweenSnapshotAndSubscribeIsNotLost() {
        FixtureTraces.commonTrace(naru.fake, p);
        AtomicBoolean once = new AtomicBoolean(true);
        naru.fake.setAfterSnapshotHook(id -> {
            if (once.getAndSet(false)) {
                naru.fake.commit(p, 2, 1, 2, 1);
            }
        });
        app.start();
        await("revision 4", Duration.ofSeconds(30), () -> "4".equals(seq()));
        assertThat(naru.fake.watchCursors()).first().isEqualTo(3L);
    }

    @Test
    void outOfRangeCursorTakesFreshSnapshot() {
        startAtThree();
        naru.fake.setAvailable(false); // disconnect the twin at cursor 3
        naru.fake.commit(p, 2, 1, 2, 1);
        naru.fake.commit(p, 2, 2, 2, 1);
        naru.fake.expireHistoryBelow(p, 5); // revisions <= 4 no longer replayable
        naru.fake.setAvailable(true); // resume Watch(after=3) -> OUT_OF_RANGE -> GetSnapshot(5) -> Watch(after=5)
        await("revision 5", Duration.ofSeconds(30), () -> "5".equals(seq()));
        assertThat(naru.fake.watchCursors()).contains(3L, 5L);
        assertThat(app.status().json().path("counters").path("diagnostic.cursor_out_of_range").asLong()).isPositive();
    }

    @Test
    void deletingOneSourceWithSharedProvenanceOnlyChangesTheSummary() {
        startAtThree();
        // canonical recount after deleting a source whose assertions are also supported elsewhere
        naru.fake.commit(p, 1, 1, 2, 1);
        // then deleting a source that exclusively supported one proposition and the chain
        naru.fake.commit(p, 1, 1, 1, 0);
        await("revision 5", Duration.ofSeconds(20), () -> "5".equals(seq()));
        assertThat(app.summary().json().path("propositions").asText()).isEqualTo("1");
        assertThat(app.summary().json().path("reasoningChains").asText()).isEqualTo("0");
        assertThat(ditto(Who.OPERATOR, "GET", policyPath(p)).status()).isEqualTo(200); // lifecycle untouched
    }

    @Test
    void profileDeletionWithDelayedUpdateAndRestartLeavesNoResurrection() {
        startAtThree();
        naru.fake.commitDeletion(p);
        naru.fake.injectToWatchers(FakeLearningProjection.state(p, 3, 1, 1, 2, 1)); // delayed update
        await("404", Duration.ofSeconds(20), () -> app.summary().status() == 404);
        assertThat(app.summary().body()).isEqualTo("{\"code\":\"twin_missing\"}");
        assertThat(ditto(Who.OPERATOR, "GET", policyPath(p)).status()).as("dedicated policy removed").isEqualTo(404);
        assertThat(DittoItSupport.thingPhysicallyAbsent(p)).as("thing removed").isTrue();
        assertThat(app.status().json().path("checkpointStatus").asText()).isEqualTo("DELETED");
        app.stop();
        app.start(); // startup recheck against the source (retained tombstone)
        await("ready", Duration.ofSeconds(30), () -> http("GET", app.base() + "/internal/health/ready", null).status() == 200);
        assertThat(app.summary().status()).isEqualTo(404);
        assertThat(ditto(Who.OPERATOR, "GET", policyPath(p)).status()).isEqualTo(404);
        assertThat(DittoItSupport.thingPhysicallyAbsent(p)).isTrue();
    }

    @Test
    void clearingOnlyTheCheckpointStoreRebuildsToTheSameState() throws Exception {
        startAtThree();
        String before = app.summary().body();
        String etag = ditto(Who.PROJECTOR, "GET", thingPath(p)).etag();
        app.stop();
        deleteRecursively(dataDir.resolve("checkpoint"));
        app.start();
        await("rebuilt", Duration.ofSeconds(30), () -> "3".equals(seq()));
        assertThat(app.summary().body()).isEqualTo(before);
        assertThat(ditto(Who.PROJECTOR, "GET", thingPath(p)).etag()).as("intact thing adopted, not rewritten").isEqualTo(etag);
    }

    @Test
    void clearingOnlyTheDittoStoreRebuildsToTheSameState() {
        startAtThree();
        String before = app.summary().body();
        DittoItSupport.purge(p); // disposable fixture: twin + policy gone, SQL cursor intact
        await("503 while missing", Duration.ofSeconds(10), () -> app.summary().status() == 503);
        Resp r = http("POST", app.base() + "/internal/v1/maintenance/profiles/" + p + "/reconcile", DittoItSupport.MAINT_TOKEN);
        assertThat(r.status()).isEqualTo(200);
        await("rebuilt", Duration.ofSeconds(20), () -> "3".equals(seq()));
        assertThat(app.summary().body()).isEqualTo(before);
        assertThat(ditto(Who.OPERATOR, "GET", policyPath(p)).status()).isEqualTo(200);
    }

    @Test
    void sourceRestoredBackwardsIsAConflictUntilExplicitReconciliation() {
        startAtThree();
        naru.fake.rewindTo(p, 2); // disaster restore of NARU
        Resp conflict = http("POST", app.base() + "/internal/v1/maintenance/profiles/" + p + "/reconcile", DittoItSupport.MAINT_TOKEN);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(app.status().json().path("checkpointStatus").asText()).isEqualTo("RESTORE_CONFLICT");
        assertThat(seq()).as("a delayed/rewound source cannot rewind the twin").isEqualTo("3");
        Resp accepted = http("POST", app.base() + "/internal/v1/maintenance/profiles/" + p + "/reconcile?acceptSourceRestore=true",
                DittoItSupport.MAINT_TOKEN);
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.json().path("result").asText()).isEqualTo("RESTORED");
        await("revision 2", Duration.ofSeconds(10), () -> "2".equals(seq()));
        naru.fake.commit(p, 1, 1, 1, 0);
        await("revision 3 after restore", Duration.ofSeconds(20), () -> "3".equals(seq())
                && "1".equals(app.summary().json().path("propositions").asText()));
    }

    @Test
    void firstBootWithoutConfirmedStateIs503() {
        naru.fake.createProfile(p);
        naru.fake.setAvailable(false);
        app.start();
        await("bootstrapping 503", Duration.ofSeconds(10), () -> app.summary().status() == 503);
        assertThat(app.summary().body()).isEqualTo("{\"code\":\"twin_unavailable\"}");
        assertThat(http("GET", app.base() + "/internal/health/ready", null).status()).isEqualTo(503);
        naru.fake.setAvailable(true);
        await("bootstrapped", Duration.ofSeconds(30), () -> "0".equals(seq()));
    }

    static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        }
    }
}
