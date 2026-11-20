package org.naruprof.twin.infra;

import java.util.Objects;

/** One validated full-replacement ProjectionState received from NARU. */
public sealed interface SourceState permits SourceState.Live, SourceState.Tombstone {

    ProfileId profileId();

    U64 sequence();

    record Live(ProfileId profileId, U64 sequence, LearningSummary summary) implements SourceState {
        public Live {
            Objects.requireNonNull(profileId);
            Objects.requireNonNull(sequence);
            Objects.requireNonNull(summary);
        }

        public TwinState toTwinState() {
            return new TwinState(sequence, summary);
        }
    }

    record Tombstone(ProfileId profileId, U64 sequence) implements SourceState {
        public Tombstone {
            Objects.requireNonNull(profileId);
            Objects.requireNonNull(sequence);
        }
    }
}
