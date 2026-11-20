package org.naruprof.twin.infra;

/** Durable lifecycle status of a profile projection (private SQL bookkeeping). */
public enum CheckpointStatus {
    /** Row exists but no native state has been confirmed yet. */
    BOOTSTRAPPING,
    /** A live revision is confirmed in Ditto. */
    LIVE,
    /** A tombstone was received; Thing/Policy removal may be unfinished. */
    PENDING_DELETION,
    /** Tombstone applied: Thing and dedicated Policy confirmed absent. Retained forever. */
    DELETED,
    /** Source moved backwards relative to confirmed state; needs explicit reconciliation. */
    RESTORE_CONFLICT
}
