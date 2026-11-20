package org.naruprof.twin.dal.ditto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class DittoPaths {
    private DittoPaths() {
    }

    /** Entity ids are "<namespace>:<name>"; the colon is legal in a path segment. */
    static String seg(String entityId) {
        return URLEncoder.encode(entityId, StandardCharsets.UTF_8).replace("%3A", ":");
    }

    static String thing(String thingId) {
        return "/things/" + seg(thingId);
    }

    static String policy(String policyId) {
        return "/policies/" + seg(policyId);
    }
}
