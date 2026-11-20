package org.naruprof.twin.dal.narugrpc;

import io.grpc.Channel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.naruprof.contract.learning.v1.LearningProjectionGrpc;
import org.naruprof.contract.learning.v1.Profile;
import org.naruprof.contract.learning.v1.ProjectionState;
import org.naruprof.contract.learning.v1.WatchRequest;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.SourceException;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client of NARU's published {@code naruprof.learning.v1.LearningProjection} facade.
 * Distinct timeout policies: a short deadline for GetSnapshot, and a maximum stream lifetime
 * for Watch after which the stream is rotated (resumed from the confirmed cursor by the caller).
 */
public class GrpcLearningSource implements LearningSource {

    private final LearningProjectionGrpc.LearningProjectionBlockingStub blocking;
    private final LearningProjectionGrpc.LearningProjectionStub async;
    private final Duration snapshotDeadline;
    private final Duration watchMaxDuration;

    public GrpcLearningSource(Channel channel, BearerCallCredentials credentials,
                              Duration snapshotDeadline, Duration watchMaxDuration) {
        this.blocking = LearningProjectionGrpc.newBlockingStub(channel).withCallCredentials(credentials);
        this.async = LearningProjectionGrpc.newStub(channel).withCallCredentials(credentials);
        this.snapshotDeadline = snapshotDeadline;
        this.watchMaxDuration = watchMaxDuration;
    }

    @Override
    public SourceState getSnapshot(ProfileId profile) {
        try {
            ProjectionState s = blocking.withDeadlineAfter(snapshotDeadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getSnapshot(Profile.newBuilder().setProfileId(profile.value()).build());
            return ProjectionStateMapper.toSource(s, profile);
        } catch (RuntimeException e) {
            throw ProjectionStateMapper.classify(e);
        }
    }

    @Override
    public Subscription watch(ProfileId profile, U64 afterSequence, int window, Listener listener) {
        AtomicReference<ClientCallStreamObserver<WatchRequest>> call = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean terminated = new AtomicBoolean();
        ClientResponseObserver<WatchRequest, ProjectionState> obs = new ClientResponseObserver<>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<WatchRequest> requestStream) {
                // backpressure: never more than `window` undelivered-to-consumer messages
                requestStream.disableAutoRequestWithInitial(Math.max(1, window));
                call.set(requestStream);
            }

            @Override
            public void onNext(ProjectionState value) {
                if (closed.get() || terminated.get()) {
                    return;
                }
                SourceState s;
                try {
                    s = ProjectionStateMapper.toSource(value, profile);
                } catch (SourceException bad) {
                    terminated.set(true);
                    ClientCallStreamObserver<WatchRequest> c = call.get();
                    if (c != null) {
                        c.cancel("incompatible message", null);
                    }
                    listener.onError(bad);
                    return;
                }
                listener.onState(s);
            }

            @Override
            public void onError(Throwable t) {
                if (closed.get() || !terminated.compareAndSet(false, true)) {
                    return;
                }
                listener.onError(ProjectionStateMapper.classify(t));
            }

            @Override
            public void onCompleted() {
                if (closed.get() || !terminated.compareAndSet(false, true)) {
                    return;
                }
                listener.onCompleted();
            }
        };
        async.withDeadlineAfter(watchMaxDuration.toMillis(), TimeUnit.MILLISECONDS)
                .watch(WatchRequest.newBuilder().setProfileId(profile.value()).setAfterSequence(afterSequence.bits()).build(), obs);
        return new Subscription() {
            @Override
            public void request(int n) {
                ClientCallStreamObserver<WatchRequest> c = call.get();
                if (c != null && !closed.get() && !terminated.get()) {
                    c.request(n);
                }
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    ClientCallStreamObserver<WatchRequest> c = call.get();
                    if (c != null) {
                        c.cancel("consumer closed the watch", null);
                    }
                }
            }
        };
    }
}
