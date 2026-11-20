package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.TwinPolicyStore.Entry;
import org.naruprof.twin.dal.api.TwinPolicyStore.Permissions;
import org.naruprof.twin.dal.api.TwinPolicyStore.PolicyDocument;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinIds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The dedicated per-profile Policy. Least privilege for Ditto 3.9.8 (verified against the real
 * gateway, see docs/access-control.md):
 * <ul>
 *   <li>operator: {@code policy:/} READ+WRITE (recoverable administrator; creates/repairs/deletes);</li>
 *   <li>projector: {@code thing:/} READ+WRITE (create, conditional full replace and delete need
 *       unrestricted root WRITE in this release) and {@code policy:/} READ (required to bind the
 *       Thing to the Policy at creation);</li>
 *   <li>reader: {@code thing:/features/learning/properties} READ only.</li>
 * </ul>
 * No grants for messages, other Things or policy mutation beyond the operator.
 */
public final class TwinPolicyTemplate {

    public static final String OPERATOR_LABEL = "naruprof-operator";
    public static final String PROJECTOR_LABEL = "naruprof-projector";
    public static final String READER_LABEL = "naruprof-reader";

    private static final Set<String> RW = Set.of("READ", "WRITE");
    private static final Set<String> R = Set.of("READ");

    private final String operatorSubject;
    private final String projectorSubject;
    private final String readerSubject;

    public TwinPolicyTemplate(String operatorSubject, String projectorSubject, String readerSubject) {
        this.operatorSubject = Objects.requireNonNull(operatorSubject);
        this.projectorSubject = Objects.requireNonNull(projectorSubject);
        this.readerSubject = Objects.requireNonNull(readerSubject);
        if (Set.of(operatorSubject, projectorSubject, readerSubject).size() != 3) {
            throw new IllegalArgumentException("operator, projector and reader must be distinct subjects");
        }
    }

    public PolicyDocument expected(ProfileId profile) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        entries.put(OPERATOR_LABEL, new Entry(Map.of(operatorSubject, "naruprof twin operator"),
                Map.of("policy:/", new Permissions(RW, Set.of()))));
        entries.put(PROJECTOR_LABEL, new Entry(Map.of(projectorSubject, "naruprof twin projector"),
                Map.of("thing:/", new Permissions(RW, Set.of()), "policy:/", new Permissions(R, Set.of()))));
        entries.put(READER_LABEL, new Entry(Map.of(readerSubject, "naruprof twin reader"),
                Map.of("thing:/features/learning/properties", new Permissions(R, Set.of()))));
        return new PolicyDocument(TwinIds.policyId(profile), entries);
    }

    /** Positively identified as this module's: our id and our operator administers it. */
    public boolean isOurs(ProfileId profile, PolicyDocument actual) {
        if (!TwinIds.policyId(profile).equals(actual.policyId())) {
            return false;
        }
        Entry op = actual.entries().get(OPERATOR_LABEL);
        return op != null && op.subjects().containsKey(operatorSubject)
                && op.resources().containsKey("policy:/")
                && op.resources().get("policy:/").grant().contains("WRITE");
    }

    /** Exact shape match (labels, subjects, resources, permission sets). */
    public boolean matches(ProfileId profile, PolicyDocument actual) {
        PolicyDocument e = expected(profile);
        return e.policyId().equals(actual.policyId()) && normalize(e.entries()).equals(normalize(actual.entries()));
    }

    private static Map<String, Entry> normalize(Map<String, Entry> m) {
        Map<String, Entry> n = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            Map<String, Permissions> res = new LinkedHashMap<>();
            v.resources().forEach((p, perm) -> res.put(p, new Permissions(Set.copyOf(perm.grant()), Set.copyOf(perm.revoke()))));
            n.put(k, new Entry(Map.copyOf(v.subjects()), Map.copyOf(res)));
        });
        return Map.copyOf(n);
    }
}
