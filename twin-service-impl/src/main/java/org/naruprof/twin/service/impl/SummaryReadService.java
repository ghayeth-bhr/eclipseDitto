package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.CorruptCheckpointException;
import org.naruprof.twin.dal.api.TwinReadModel;
import org.naruprof.twin.dal.api.TwinReadModel.LearningRead;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinState;
import org.naruprof.twin.infra.TwinSummaryView;
import org.naruprof.twin.service.api.SummaryResult;

import java.util.Optional;

/**
 * Private summary read: reads the ACTUAL Thing through the read-only identity. Never fills
 * missing state with zeros: no confirmed state means 503, confirmed deletion means 404.
 */
public class SummaryReadService {

    private final ProfileId configuredProfile;
    private final CheckpointStore checkpoints;
    private final TwinReadModel reader;
    private final ProjectionDiagnostics diag;

    public SummaryReadService(ProfileId configuredProfile, CheckpointStore checkpoints, TwinReadModel reader,
                              ProjectionDiagnostics diag) {
        this.configuredProfile = configuredProfile;
        this.checkpoints = checkpoints;
        this.reader = reader;
        this.diag = diag;
    }

    public SummaryResult read(String profileIdText) {
        ProfileId requested;
        try {
            requested = ProfileId.parse(profileIdText);
        } catch (IllegalArgumentException e) {
            return new SummaryResult.Invalid();
        }
        if (!requested.equals(configuredProfile)) {
            return new SummaryResult.Forbidden();
        }
        Optional<Checkpoint> cp;
        try {
            cp = checkpoints.find(requested);
        } catch (CorruptCheckpointException e) {
            return new SummaryResult.Unavailable("checkpoint_unreadable");
        } catch (RuntimeException e) {
            return new SummaryResult.Unavailable("checkpoint_store_unavailable");
        }
        if (cp.isEmpty() || cp.get().status() == CheckpointStatus.BOOTSTRAPPING) {
            return new SummaryResult.Unavailable("bootstrapping");
        }
        if (cp.get().isRetired()) {
            return new SummaryResult.Missing();
        }
        try {
            LearningRead r = reader.readLearning(requested);
            if (r instanceof LearningRead.Found f) {
                Optional<TwinState> s = LearningFeatureCodec.decode(f.properties());
                if (s.isEmpty()) {
                    diag.count("read.incompatible_twin");
                    return new SummaryResult.Unavailable("twin_incompatible");
                }
                diag.count("read.ok");
                return new SummaryResult.Found(TwinSummaryView.of(requested, s.get()));
            }
            diag.count("read.not_visible");
            return new SummaryResult.Unavailable("twin_not_readable");
        } catch (TwinStoreException e) {
            diag.count("read.failed." + e.kind());
            return new SummaryResult.Unavailable("ditto_" + e.kind().name().toLowerCase());
        }
    }
}
