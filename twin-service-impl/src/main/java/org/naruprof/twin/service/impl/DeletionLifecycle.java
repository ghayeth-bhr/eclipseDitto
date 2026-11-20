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
import org.naruprof.twin.infra.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Profile deletion as a lifecycle transition: persist intent -> delete Thing (projector,
 * conditional) -> verify absence -> operator removes the dedicated Policy -> verify -> record the
 * applied tombstone. Every step is idempotent so an interrupted deletion resumes after restart.
 */
public class DeletionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeletionLifecycle.class);

    private final TwinThingStore things;
    private final CheckpointStore checkpoints;
    private final LeaseStore leases;
    private final ProvisioningService provisioning;
    private final ProjectionService projection;
    private final ProjectionDiagnostics diag;
    private final int maxAttempts;

    public DeletionLifecycle(TwinThingStore things, CheckpointStore checkpoints, LeaseStore leases,
                             ProvisioningService provisioning, ProjectionService projection,
                             ProjectionDiagnostics diag, int maxAttempts) {
        this.things = things;
        this.checkpoints = checkpoints;
        this.leases = leases;
        this.provisioning = provisioning;
        this.projection = projection;
        this.diag = diag;
        this.maxAttempts = maxAttempts;
    }

    public void begin(SourceState.Tombstone t, Lease lease) {
        ProfileId profile = t.profileId();
        Optional<Checkpoint> cp = checkpoints.find(profile);
        if (cp.isPresent() && cp.get().applied().isPresent() && cp.get().status() == CheckpointStatus.LIVE
                && t.sequence().compareTo(cp.get().applied().get()) <= 0) {
            diag.diagnostic("stale_tombstone", profile.value(), "tombstone=" + t.sequence() + " applied=" + cp.get().applied().get());
            return;
        }
        if (cp.isEmpty() || cp.get().status() != CheckpointStatus.DELETED) {
            checkpoints.markPendingDeletion(profile, t.sequence(), lease); // durable intent first
            log.info("deletion intent persisted profile={} tombstone={}", profile, t.sequence());
        }
        resume(profile, lease);
    }

    /** Completes (or re-verifies) a pending or applied deletion. */
    public void resume(ProfileId profile, Lease lease) {
        Checkpoint cp = checkpoints.find(profile).orElseThrow(() -> new IllegalStateException("no deletion intent"));
        if (!cp.isRetired()) {
            throw new IllegalStateException("profile is not retired");
        }
        U64 tomb = cp.tombstoneSequence().orElseThrow();
        removeThing(profile, lease);
        provisioning.removePolicy(profile, lease);
        if (cp.status() != CheckpointStatus.DELETED) {
            checkpoints.markDeleted(profile, tomb, lease);
            log.info("tombstone applied profile={} sequence={}", profile, tomb);
            diag.count("profile.deleted");
        }
    }

    private void removeThing(ProfileId profile, Lease lease) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            leases.assertHeld(lease);
            ThingRead r = things.read(profile);
            if (r instanceof ThingRead.Absent) {
                // verified absent with the lifecycle (projector) identity, while our policy still grants it
                return;
            }
            ThingRead.Present p = (ThingRead.Present) r;
            projection.verifyOwnership(profile, p); // narrow cleanup to positively identified things
            try {
                WriteOutcome o = things.delete(profile, p.etag());
                if (o == WriteOutcome.APPLIED) {
                    diag.count("thing.deleted");
                }
            } catch (TwinStoreException e) {
                if (e.kind() != Kind.UNKNOWN_OUTCOME) {
                    throw e;
                }
            }
        }
        throw new TwinStoreException(Kind.UNAVAILABLE, "thing deletion retries exhausted");
    }
}
