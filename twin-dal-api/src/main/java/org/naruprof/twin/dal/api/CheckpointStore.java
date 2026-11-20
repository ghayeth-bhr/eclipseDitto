package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.U64;

import java.util.Optional;

/**
 * Private SQL bookkeeping: one row per profile (unique key), monotonically advancing cursor and
 * explicit lifecycle status. Every mutating call is fenced by the caller's lease token and fails
 * with {@link FencedOutException} if another writer has taken over.
 */
public interface CheckpointStore {

    /** @throws CorruptCheckpointException if the stored row cannot be interpreted */
    Optional<Checkpoint> find(ProfileId profile);

    /** Create the BOOTSTRAPPING row if absent. */
    void ensureRow(ProfileId profile, Lease lease);

    /**
     * Records a confirmed live revision. Monotonic: a lower value never replaces a higher one
     * (returns false); equal is an idempotent no-op returning true. Refused for retired profiles.
     */
    boolean confirmApplied(ProfileId profile, U64 sequence, Lease lease);

    /** Explicitly reset the cursor (accepted disaster restore / repair). Emits no monotonic check. */
    void resetApplied(ProfileId profile, U64 sequence, Lease lease, String reason);

    void markPendingDeletion(ProfileId profile, U64 tombstoneSequence, Lease lease);

    void markDeleted(ProfileId profile, U64 tombstoneSequence, Lease lease);

    void markRestoreConflict(ProfileId profile, U64 sourceSequence, Lease lease);

    /** Drop an unreadable row so the profile re-bootstraps from a snapshot. */
    void discardCorrupt(ProfileId profile, Lease lease);
}
