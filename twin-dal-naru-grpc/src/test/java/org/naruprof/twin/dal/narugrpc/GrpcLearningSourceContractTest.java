package org.naruprof.twin.dal.narugrpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.naruprof.contract.learning.v1.ProjectionState;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.fixture.naru.BearerAuthInterceptor;
import org.naruprof.twin.fixture.naru.FakeLearningProjection;
import org.naruprof.twin.fixture.naru.FixtureTraces;
import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The generated client against the fixture publisher: wire mapping and status classification. */
class GrpcLearningSourceContractTest {

    static final String PID = FixtureTraces.CONTRACT_PROFILE;
    static final ProfileId P = ProfileId.parse(PID);

    FakeLearningProjection naru;
    Server server;
    ManagedChannel channel;

    @BeforeEach
    void up() throws Exception {
        naru = new FakeLearningProjection();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(naru, new BearerAuthInterceptor("test-only-twin-to-naru")))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void down() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    GrpcLearningSource client(String token) {
        return new GrpcLearningSource(channel, new BearerCallCredentials(token.toCharArray()),
                Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    static final class Collect implements LearningSource.Listener {
        final List<SourceState> states = new CopyOnWriteArrayList<>();
        final List<SourceException> errors = new CopyOnWriteArrayList<>();

        public void onState(SourceState s) { states.add(s); }
        public void onError(SourceException e) { errors.add(e); }
        public void onCompleted() { }
    }

    @Test
    void emptyProfileSnapshotIsRevisionZeroWithZeroCounts() {
        naru.createProfile(PID);
        assertThat(client("test-only-twin-to-naru").getSnapshot(P))
                .isEqualTo(new SourceState.Live(P, U64.ZERO, LearningSummary.EMPTY));
    }

    @Test
    void commonTraceSnapshotAndExclusiveWatchReplay() {
        FixtureTraces.commonTrace(naru, PID);
        GrpcLearningSource c = client("test-only-twin-to-naru");
        assertThat(c.getSnapshot(P)).isEqualTo(new SourceState.Live(P, U64.of(3), LearningSummary.of(1, 1, 2, 1)));
        Collect col = new Collect();
        LearningSource.Subscription s = c.watch(P, U64.of(1), 8, col);
        assertThat(col.states).extracting(SourceState::sequence).containsExactly(U64.of(2), U64.of(3));
        naru.commitDeletion(PID);
        assertThat(col.states.get(2)).isEqualTo(new SourceState.Tombstone(P, U64.of(4)));
        s.close();
    }

    @Test
    void flowControlWindowLimitsDeliveryUntilRequested() {
        FixtureTraces.commonTrace(naru, PID);
        Collect col = new Collect();
        LearningSource.Subscription s = client("test-only-twin-to-naru").watch(P, U64.ZERO, 1, col);
        assertThat(col.states).hasSize(1);
        s.request(1);
        assertThat(col.states).hasSize(2);
        s.request(5);
        assertThat(col.states).hasSize(3);
        s.close();
    }

    @Test
    void statusesAreClassified() {
        GrpcLearningSource ok = client("test-only-twin-to-naru");
        assertThatThrownBy(() -> ok.getSnapshot(P)).isInstanceOf(SourceException.class)
                .extracting(e -> ((SourceException) e).kind()).isEqualTo(SourceException.Kind.NOT_FOUND);
        naru.createProfile(PID);
        assertThatThrownBy(() -> client("wrong").getSnapshot(P))
                .extracting(e -> ((SourceException) e).kind()).isEqualTo(SourceException.Kind.AUTH);
        Collect col = new Collect();
        ok.watch(P, U64.of(9), 4, col); // ahead of head
        assertThat(col.errors).extracting(SourceException::kind).containsExactly(SourceException.Kind.OUT_OF_RANGE);
        naru.setAvailable(false);
        assertThatThrownBy(() -> ok.getSnapshot(P))
                .extracting(e -> ((SourceException) e).kind()).isEqualTo(SourceException.Kind.TRANSIENT);
    }

    @Test
    void malformedMessagesAreIncompatibleAndCancelTheStream() {
        naru.createProfile(PID);
        Collect col = new Collect();
        client("test-only-twin-to-naru").watch(P, U64.ZERO, 4, col);
        naru.injectToWatchers(ProjectionState.newBuilder().setProfileId(PID).setSequence(1).build()); // oneof unset
        assertThat(col.errors).extracting(SourceException::kind).containsExactly(SourceException.Kind.INCOMPATIBLE);
        Collect other = new Collect();
        client("test-only-twin-to-naru").watch(P, U64.ZERO, 4, other);
        naru.injectToWatchers(FakeLearningProjection.state("00000000-0000-4000-8000-000000000000", 1, 1, 0, 0, 0));
        assertThat(other.errors).extracting(SourceException::kind).containsExactly(SourceException.Kind.INCOMPATIBLE);
    }

    @Test
    void uint64SequencesAboveSignedRangeSurviveTheWire() {
        naru.createProfile(PID);
        Collect col = new Collect();
        client("test-only-twin-to-naru").watch(P, U64.ZERO, 4, col);
        naru.injectToWatchers(FakeLearningProjection.state(PID, -2L, 1, 0, 0, 0)); // 2^64-2
        assertThat(col.states.get(0).sequence().toString()).isEqualTo("18446744073709551614");
    }
}
