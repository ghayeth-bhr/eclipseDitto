package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.naruprof.twin.dal.api.TwinThingStore;
import org.naruprof.twin.dal.api.TwinThingStore.ThingRead;
import org.naruprof.twin.dal.api.WriteOutcome;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.TwinIds;
import org.naruprof.twin.infra.TwinState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Applies one full-replacement live state to the native twin. Ordering is decided on the NARU
 * sequence (uint64) read back from Ditto; concurrency is protected by Ditto's own revision ETag.
 * The checkpoint is advanced only after the native state is confirmed. No transaction spans
 * Ditto and SQL.
 */
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    public enum Mode {
        /** Streamed event: equal-sequence mismatch is drift, lower sequence is ignored. */
        NORMAL,
        /** Fresh snapshot from the source of truth: may repair equal-version drift. */
        AUTHORITATIVE,
        /** Operator-accepted disaster restore: may move the twin backwards. */
        RESTORE
    }

    private final TwinThingStore things;
    private final CheckpointStore checkpoints;
    private final LeaseStore leases;
    private final ProvisioningService provisioning;
    private final ProjectionDiagnostics diag;
    private final int maxAttempts;

    public ProjectionService(TwinThingStore things, CheckpointStore checkpoints, LeaseStore leases,
                             ProvisioningService provisioning, ProjectionDiagnostics diag, int maxAttempts) {
        this.things = things;
        this.checkpoints = checkpoints;
        this.leases = leases;
        this.provisioning = provisioning;
        this.diag = diag;
        this.maxAttempts = maxAttempts;
    }

    public ApplyResult applyLive(SourceState.Live s, Lease lease, Mode mode) {
        ProfileId profile = s.profileId();
        Optional<Checkpoint> cp = checkpoints.find(profile);
        if (cp.isPresent() && cp.get().isRetired() && mode != Mode.RESTORE) {
            diag.diagnostic("stale_event_for_retired_id", profile.value(), "sequence=" + s.sequence());
            return ApplyResult.RETIRED_IGNORED;
        }
        if (cp.isPresent() && cp.get().status() == CheckpointStatus.RESTORE_CONFLICT && mode != Mode.RESTORE) {
            return ApplyResult.CONFLICT_PENDING;
        }
        TwinState target = s.toTwinState();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            leases.assertHeld(lease); // fence before every native write decision
            ThingRead read = things.read(profile);
            if (read instanceof ThingRead.Absent) {
                provisioning.ensurePolicy(profile, lease);
                leases.assertHeld(lease);
                WriteOutcome o;
                try {
                    o = things.create(profile, TwinIds.policyId(profile), LearningFeatureCodec.encode(target));
                } catch (TwinStoreException e) {
                    if (e.kind() == Kind.UNKNOWN_OUTCOME) {
                        diag.count("write.unknown_outcome");
                        continue; // resolve by reading back
                    }
                    throw e;
                }
                if (o == WriteOutcome.CREATED || o == WriteOutcome.APPLIED) {
                    confirm(profile, target, lease, mode);
                    log.info("twin created profile={} sequence={} attempt={}", profile, target.sequence(), attempt);
                    diag.count("thing.created");
                    return ApplyResult.APPLIED;
                }
                diag.count("write.precondition_retry");
                continue;
            }
            ThingRead.Present p = (ThingRead.Present) read;
            verifyOwnership(profile, p);
            Optional<TwinState> current = LearningFeatureCodec.decode(p.learningProperties().orElse(null));
            boolean extraneous = !p.otherFeatures().isEmpty();
            boolean repair = false;
            if (current.isPresent()) {
                int cmp = target.sequence().compareTo(current.get().sequence());
                if (cmp < 0 && mode != Mode.RESTORE) {
                    diag.count("event.ignored_stale");
                    log.debug("ignoring lower revision profile={} event={} twin={}", profile, target.sequence(), current.get().sequence());
                    return ApplyResult.IGNORED_STALE;
                }
                if (cmp == 0) {
                    if (current.get().equals(target) && !extraneous) {
                        confirm(profile, target, lease, mode);
                        diag.count("event.already_applied");
                        return ApplyResult.ALREADY_APPLIED;
                    }
                    if (mode == Mode.NORMAL) {
                        diag.diagnostic("drift_equal_sequence", profile.value(), "sequence=" + target.sequence());
                        return ApplyResult.DRIFT;
                    }
                    repair = true;
                }
            } else {
                if (mode == Mode.NORMAL) {
                    diag.diagnostic("drift_malformed_feature", profile.value(), "owned feature unreadable");
                    return ApplyResult.DRIFT;
                }
                repair = true;
            }
            if (extraneous) {
                repair = true;
            }
            leases.assertHeld(lease);
            WriteOutcome o;
            try {
                o = things.replace(profile, p, LearningFeatureCodec.encode(target));
            } catch (TwinStoreException e) {
                if (e.kind() == Kind.UNKNOWN_OUTCOME) {
                    diag.count("write.unknown_outcome");
                    continue;
                }
                throw e;
            }
            if (o == WriteOutcome.APPLIED) {
                confirm(profile, target, lease, mode);
                if (repair) {
                    diag.diagnostic("twin_repaired", profile.value(), "mode=" + mode + " sequence=" + target.sequence());
                    return ApplyResult.REPAIRED;
                }
                log.info("twin updated profile={} sequence={} attempt={}", profile, target.sequence(), attempt);
                diag.count("thing.updated");
                return ApplyResult.APPLIED;
            }
            // 412 (stale ETag) or 404 (deleted meanwhile): reread and decide again
            diag.count("write.precondition_retry");
        }
        throw new TwinStoreException(Kind.UNAVAILABLE, "bounded write retries exhausted");
    }

    private void confirm(ProfileId profile, TwinState target, Lease lease, Mode mode) {
        if (mode == Mode.RESTORE) {
            checkpoints.resetApplied(profile, target.sequence(), lease, "accepted source restore");
        } else {
            checkpoints.confirmApplied(profile, target.sequence(), lease);
        }
    }

    /** The Thing must be ours: our id and bound to our dedicated Policy. */
    void verifyOwnership(ProfileId profile, ThingRead.Present p) {
        if (!TwinIds.thingId(profile).equals(p.thingId()) || !TwinIds.policyId(profile).equals(p.policyId())) {
            diag.diagnostic("thing_conflict", profile.value(), "thing bound to an unexpected policy");
            throw new TwinStoreException(Kind.CONFLICT, "thing is not bound to this module's policy");
        }
    }

    /** Native twin state as seen by the projector (empty if absent or unreadable). */
    public Optional<TwinState> currentTwin(ProfileId profile) {
        ThingRead r = things.read(profile);
        if (r instanceof ThingRead.Present p) {
            verifyOwnership(profile, p);
            return LearningFeatureCodec.decode(p.learningProperties().orElse(null));
        }
        return Optional.empty();
    }
}
