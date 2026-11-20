package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.CorruptCheckpointException;
import org.naruprof.twin.dal.api.LearningSource;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.TwinState;
import org.naruprof.twin.infra.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Bootstrap and periodic reconciliation from the source of truth. Converges every combination
 * of {checkpoint present/absent/corrupt} x {Thing present/absent/drifted} to the authoritative
 * snapshot, and refuses to silently rewind when the source moved backwards.
 */
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final LearningSource source;
    private final CheckpointStore checkpoints;
    private final ProjectionService projection;
    private final ProvisioningService provisioning;
    private final DeletionLifecycle deletion;
    private final ProjectionDiagnostics diag;

    public ReconcileService(LearningSource source, CheckpointStore checkpoints, ProjectionService projection,
                            ProvisioningService provisioning, DeletionLifecycle deletion, ProjectionDiagnostics diag) {
        this.source = source;
        this.checkpoints = checkpoints;
        this.projection = projection;
        this.provisioning = provisioning;
        this.deletion = deletion;
        this.diag = diag;
    }

    /** Reads the checkpoint, discarding a corrupt row (it is rebuilt from the snapshot). */
    public Optional<Checkpoint> loadCheckpoint(ProfileId profile, Lease lease) {
        try {
            return checkpoints.find(profile);
        } catch (CorruptCheckpointException e) {
            diag.diagnostic("corrupt_checkpoint", profile.value(), "discarding row; rebuilding from snapshot");
            checkpoints.discardCorrupt(profile, lease);
            return Optional.empty();
        }
    }

    public ReconcileResult reconcile(ProfileId profile, Lease lease, boolean acceptSourceRestore) {
        Optional<Checkpoint> cp = loadCheckpoint(profile, lease);
        checkpoints.ensureRow(profile, lease);
        long t0 = System.nanoTime();
        SourceState snapshot = source.getSnapshot(profile); // SourceException propagates, classified
        diag.count("source.snapshot");
        diag.resetSourceSequence(snapshot.sequence());
        log.info("snapshot profile={} sequence={} type={} in {}ms", profile, snapshot.sequence(),
                snapshot instanceof SourceState.Tombstone ? "tombstone" : "summary", (System.nanoTime() - t0) / 1_000_000);

        if (snapshot instanceof SourceState.Tombstone t) {
            deletion.begin(t, lease);
            diag.reconciled();
            return new ReconcileResult(ReconcileResult.Kind.DELETED, t.sequence());
        }
        SourceState.Live live = (SourceState.Live) snapshot;

        if (cp.isPresent() && cp.get().isRetired()) {
            if (cp.get().status() == CheckpointStatus.PENDING_DELETION) {
                // intent persisted but the source now says live: never resurrect silently
                diag.diagnostic("retired_but_source_live", profile.value(), "snapshot=" + live.sequence());
            } else {
                diag.diagnostic("retired_but_source_live", profile.value(), "snapshot=" + live.sequence());
            }
            if (!acceptSourceRestore) {
                // keep retirement; finish/verify cleanup
                deletion.resume(profile, lease);
                diag.reconciled();
                return new ReconcileResult(ReconcileResult.Kind.DELETED, cp.get().tombstoneSequence().orElseThrow());
            }
        }

        Optional<TwinState> twin = projection.currentTwin(profile);
        U64 confirmedHigh = null;
        if (cp.isPresent() && !cp.get().isRetired() && cp.get().applied().isPresent()) {
            confirmedHigh = cp.get().applied().get();
        }
        if (twin.isPresent() && (confirmedHigh == null || twin.get().sequence().isGreaterThan(confirmedHigh))) {
            confirmedHigh = twin.get().sequence();
        }
        boolean backwards = confirmedHigh != null && live.sequence().isLessThan(confirmedHigh);
        boolean inConflict = cp.isPresent() && cp.get().status() == CheckpointStatus.RESTORE_CONFLICT;

        if ((backwards || inConflict) && !acceptSourceRestore) {
            if (!inConflict) {
                checkpoints.markRestoreConflict(profile, live.sequence(), lease);
            }
            diag.diagnostic("restore_conflict", profile.value(), "source=" + live.sequence() + " confirmed=" + confirmedHigh);
            diag.reconciled();
            return new ReconcileResult(ReconcileResult.Kind.RESTORE_CONFLICT, confirmedHigh);
        }
        if (backwards || inConflict || (cp.isPresent() && cp.get().isRetired())) {
            projection.applyLive(live, lease, ProjectionService.Mode.RESTORE);
            diag.diagnostic("restore_accepted", profile.value(), "twin and cursor moved to source=" + live.sequence());
            provisioning.ensurePolicy(profile, lease);
            diag.reconciled();
            return new ReconcileResult(ReconcileResult.Kind.RESTORED, live.sequence());
        }

        ApplyResult r = projection.applyLive(live, lease, ProjectionService.Mode.AUTHORITATIVE);
        if (twin.isEmpty() && cp.flatMap(Checkpoint::applied).isPresent()) {
            diag.diagnostic("twin_rebuilt", profile.value(), "native state was missing; recreated at " + live.sequence());
        }
        if (cp.isEmpty() || cp.get().applied().isEmpty()) {
            if (twin.isPresent()) {
                diag.diagnostic("checkpoint_rebuilt", profile.value(), "adopted existing twin at " + live.sequence());
            }
        }
        // verify the Policy even if the checkpoint looked current
        provisioning.ensurePolicy(profile, lease);
        diag.reconciled();
        log.info("reconciled profile={} sequence={} result={}", profile, live.sequence(), r);
        return new ReconcileResult(ReconcileResult.Kind.LIVE, live.sequence());
    }
}
