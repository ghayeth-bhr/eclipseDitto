package org.naruprof.twin.service.impl;

import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.TwinState;
import org.naruprof.twin.infra.U64;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one bounded feature "learning": exactly five properties, all canonical unsigned decimal
 * strings. Anything else (extra keys such as ratings, numbers instead of strings, missing keys)
 * is not a valid owned state.
 */
public final class LearningFeatureCodec {

    public static final String SEQUENCE = "sequence";
    public static final String TEACHING = "teachingSessions";
    public static final String PRACTICE = "practiceSessions";
    public static final String PROPOSITIONS = "propositions";
    public static final String CHAINS = "reasoningChains";
    static final Set<String> KEYS = Set.of(SEQUENCE, TEACHING, PRACTICE, PROPOSITIONS, CHAINS);

    private LearningFeatureCodec() {
    }

    public static Map<String, String> encode(TwinState s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(SEQUENCE, s.sequence().toString());
        m.put(TEACHING, s.summary().teachingSessions().toString());
        m.put(PRACTICE, s.summary().practiceSessions().toString());
        m.put(PROPOSITIONS, s.summary().propositions().toString());
        m.put(CHAINS, s.summary().reasoningChains().toString());
        return m;
    }

    public static Optional<TwinState> decode(Map<String, Object> props) {
        if (props == null || !props.keySet().equals(KEYS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TwinState(u(props, SEQUENCE), new LearningSummary(u(props, TEACHING),
                    u(props, PRACTICE), u(props, PROPOSITIONS), u(props, CHAINS))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static U64 u(Map<String, Object> m, String k) {
        if (!(m.get(k) instanceof String s)) {
            throw new IllegalArgumentException("not a string");
        }
        return U64.parse(s);
    }
}
