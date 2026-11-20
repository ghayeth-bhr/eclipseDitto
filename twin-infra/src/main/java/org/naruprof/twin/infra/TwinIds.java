package org.naruprof.twin.infra;

/** Ditto entity ids owned by this module. Namespace and naming are fixed by the module contract. */
public final class TwinIds {

    public static final String NAMESPACE = "naruprof.learning";
    public static final String FEATURE = "learning";

    private TwinIds() {
    }

    /** {@code naruprof.learning:<uuid>} */
    public static String thingId(ProfileId p) {
        return NAMESPACE + ":" + p.value();
    }

    /** {@code naruprof.learning:policy-<uuid>} */
    public static String policyId(ProfileId p) {
        return NAMESPACE + ":policy-" + p.value();
    }
}
