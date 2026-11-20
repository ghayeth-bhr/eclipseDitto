package org.naruprof.twin.service.impl;

import org.naruprof.twin.infra.U64;

/** Outcome of reconciling native state and checkpoint against a fresh authoritative snapshot. */
public record ReconcileResult(Kind kind, U64 appliedSequence) {
    public enum Kind {
        /** Twin matches the snapshot (written, adopted or repaired); watch from appliedSequence. */
        LIVE,
        /** Tombstone applied and cleanup verified. */
        DELETED,
        /** Source is behind confirmed state: requires explicit reconciliation. */
        RESTORE_CONFLICT,
        /** Operator accepted the restored source; twin and cursor were moved to it. */
        RESTORED
    }
}
