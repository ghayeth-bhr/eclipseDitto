package org.naruprof.twin.dal.api;

import java.util.Map;
import java.util.Set;

/** Operator-identity port onto the dedicated per-profile Policy. */
public interface TwinPolicyStore {

    PolicyRead read(String policyId);

    /** PUT with {@code If-None-Match: *}. */
    WriteOutcome create(PolicyDocument policy);

    /** PUT with {@code If-Match: <etag>}. */
    WriteOutcome replace(PolicyDocument policy, String etag);

    /** DELETE with {@code If-Match: <etag>}. */
    WriteOutcome delete(String policyId, String etag);

    sealed interface PolicyRead {
        record Absent() implements PolicyRead {
        }

        record Present(String etag, PolicyDocument policy) implements PolicyRead {
        }
    }

    /** Normalized policy: label -> entry. */
    record PolicyDocument(String policyId, Map<String, Entry> entries) {
    }

    /** subjects: subject id -> type; resources: resource path ("thing:/...") -> grant/revoke. */
    record Entry(Map<String, String> subjects, Map<String, Permissions> resources) {
    }

    record Permissions(Set<String> grant, Set<String> revoke) {
    }
}
