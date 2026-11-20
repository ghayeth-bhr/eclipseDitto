package org.naruprof.twin.infra;

import java.util.Objects;

/** The complete owned state of one twin: applied NARU revision plus summary. */
public record TwinState(U64 sequence, LearningSummary summary) {
    public TwinState {
        Objects.requireNonNull(sequence);
        Objects.requireNonNull(summary);
    }
}
