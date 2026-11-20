package org.naruprof.twin.infra;

import java.util.Objects;

/**
 * The four projected counts. Only counts: no titles, labels, ratings, prompts or other learner
 * content can be represented here.
 */
public record LearningSummary(U64 teachingSessions, U64 practiceSessions, U64 propositions, U64 reasoningChains) {

    public static final LearningSummary EMPTY = new LearningSummary(U64.ZERO, U64.ZERO, U64.ZERO, U64.ZERO);

    public LearningSummary {
        Objects.requireNonNull(teachingSessions);
        Objects.requireNonNull(practiceSessions);
        Objects.requireNonNull(propositions);
        Objects.requireNonNull(reasoningChains);
    }

    public static LearningSummary of(long teaching, long practice, long propositions, long chains) {
        return new LearningSummary(U64.of(teaching), U64.of(practice), U64.of(propositions), U64.of(chains));
    }
}
