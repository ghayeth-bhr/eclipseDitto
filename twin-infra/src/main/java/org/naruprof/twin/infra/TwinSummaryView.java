package org.naruprof.twin.infra;

/**
 * Exactly the six-field private summary response. All values are unsigned decimal strings.
 * Field order matches the integration contract example.
 */
public record TwinSummaryView(String profileId, String sequence, String teachingSessions,
                              String practiceSessions, String propositions, String reasoningChains) {

    public static TwinSummaryView of(ProfileId p, TwinState s) {
        LearningSummary c = s.summary();
        return new TwinSummaryView(p.value(), s.sequence().toString(), c.teachingSessions().toString(),
                c.practiceSessions().toString(), c.propositions().toString(), c.reasoningChains().toString());
    }
}
