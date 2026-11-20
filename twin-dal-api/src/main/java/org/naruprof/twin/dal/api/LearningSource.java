package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.U64;

/**
 * NARU's published LearningProjection facade, seen as a semantic source port. Implementations
 * validate wire messages and only ever surface {@link SourceState} values or
 * {@link SourceException}s.
 */
public interface LearningSource {

    /** Consistent full snapshot (snapshot deadline applies). */
    SourceState getSnapshot(ProfileId profile);

    /**
     * Replays revisions greater than {@code afterSequence} in order, then tails. Callbacks are
     * delivered on transport threads; the listener must hand off quickly. Flow control: at most
     * {@code window} messages are delivered until the consumer calls {@link Subscription#request}.
     */
    Subscription watch(ProfileId profile, U64 afterSequence, int window, Listener listener);

    interface Listener {
        void onState(SourceState state);

        void onError(SourceException error);

        void onCompleted();
    }

    interface Subscription extends AutoCloseable {
        /** Grant the producer {@code n} more messages (consumer finished processing). */
        void request(int n);

        /** Cancel the stream; idempotent. */
        @Override
        void close();
    }
}
