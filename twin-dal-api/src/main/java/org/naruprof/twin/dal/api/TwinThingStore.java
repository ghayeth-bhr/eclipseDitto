package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.ProfileId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Projector-identity port onto the persisted twin (one Thing per profile). Every mutation is
 * conditional on Ditto's native revision/ETag; the NARU sequence is only data inside the feature.
 */
public interface TwinThingStore {

    ThingRead read(ProfileId profile);

    /** Create with {@code If-None-Match: *}, binding {@code policyId} explicitly. */
    WriteOutcome create(ProfileId profile, String policyId, Map<String, String> learningProperties);

    /**
     * Replace the whole Thing with {@code If-Match: <etag>}: same policyId, same preserved
     * unrelated fields, new learning properties, all in one write.
     */
    WriteOutcome replace(ProfileId profile, ThingRead.Present base, Map<String, String> learningProperties);

    /** Delete with {@code If-Match: <etag>}. */
    WriteOutcome delete(ProfileId profile, String etag);

    sealed interface ThingRead {
        /** Not visible to the projector (absent, or hidden by Ditto's existence hiding). */
        record Absent() implements ThingRead {
        }

        /**
         * @param learningProperties raw property values of feature "learning", empty if the feature is absent
         * @param otherFeatures      ids of any features other than "learning"
         * @param preserved          permitted unrelated top-level fields (attributes, definition) to carry over
         */
        record Present(String etag, String thingId, String policyId, Optional<Map<String, Object>> learningProperties,
                       Set<String> otherFeatures, Map<String, Object> preserved) implements ThingRead {
        }
    }
}
