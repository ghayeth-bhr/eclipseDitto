package org.naruprof.twin.infra;

import java.time.Instant;
import java.util.Optional;

/**
 * Consumer bookkeeping for one profile.
 *
 * @param applied            last revision confirmed in Ditto (empty while bootstrapping)
 * @param tombstoneSequence  source sequence of the deletion intent / applied tombstone
 * @param conflictSequence   source sequence that triggered a restore conflict
 */
public record Checkpoint(ProfileId profileId, CheckpointStatus status, Optional<U64> applied,
                         Optional<U64> tombstoneSequence, Optional<U64> conflictSequence, Instant updatedAt) {

    public boolean isRetired() {
        return status == CheckpointStatus.PENDING_DELETION || status == CheckpointStatus.DELETED;
    }
}
