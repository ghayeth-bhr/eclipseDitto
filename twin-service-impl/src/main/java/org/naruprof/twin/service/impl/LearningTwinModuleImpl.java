package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.TwinReadModel;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.U64;
import org.naruprof.twin.service.api.LearningTwinModule;
import org.naruprof.twin.service.api.ProjectionStatusView;
import org.naruprof.twin.service.api.Readiness;
import org.naruprof.twin.service.api.ReconcileOutcome;
import org.naruprof.twin.service.api.SummaryResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Local implementation of the published facade: delegates to the private services. */
public class LearningTwinModuleImpl implements LearningTwinModule {

    private final ProfileId configuredProfile;
    private final SummaryReadService reads;
    private final ProfileProjector projector;
    private final CheckpointStore checkpoints;
    private final TwinReadModel reader;
    private final ProjectionDiagnostics diag;
    private final Supplier<String> circuitState;

    public LearningTwinModuleImpl(ProfileId configuredProfile, SummaryReadService reads, ProfileProjector projector,
                                  CheckpointStore checkpoints, TwinReadModel reader, ProjectionDiagnostics diag,
                                  Supplier<String> circuitState) {
        this.configuredProfile = configuredProfile;
        this.reads = reads;
        this.projector = projector;
        this.checkpoints = checkpoints;
        this.reader = reader;
        this.diag = diag;
        this.circuitState = circuitState;
    }

    @Override
    public SummaryResult readSummary(String profileIdText) {
        return reads.read(profileIdText);
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public Readiness readiness() {
        boolean authenticated;
        String detail = "ok";
        try {
            reader.verifyAuthenticated();
            authenticated = true;
        } catch (RuntimeException e) {
            authenticated = false;
            detail = "native read failed";
        }
        boolean initialized;
        try {
            Optional<Checkpoint> cp = checkpoints.find(configuredProfile);
            initialized = cp.isPresent() && cp.get().status() != CheckpointStatus.BOOTSTRAPPING;
        } catch (RuntimeException e) {
            initialized = false;
        }
        if (authenticated && !initialized) {
            detail = "projection not initialized";
        }
        return new Readiness(authenticated && initialized, authenticated, initialized, detail);
    }

    @Override
    public ProjectionStatusView status() {
        Optional<Checkpoint> cp;
        String cpStatus;
        try {
            cp = checkpoints.find(configuredProfile);
            cpStatus = cp.map(c -> c.status().name()).orElse("ABSENT");
        } catch (RuntimeException e) {
            cp = Optional.empty();
            cpStatus = "UNREADABLE";
        }
        String applied = cp.flatMap(Checkpoint::applied).map(U64::toString).orElse(null);
        U64 src = diag.lastSourceSequence();
        String lag = null;
        if (src != null && cp.flatMap(Checkpoint::applied).isPresent()) {
            U64 a = cp.get().applied().get();
            lag = src.isGreaterThan(a) ? Long.toUnsignedString(src.bits() - a.bits()) : "0";
        }
        Instant fa = diag.lastFailureAt();
        Instant ra = diag.lastReconcileAt();
        return new ProjectionStatusView(configuredProfile.value(), cpStatus, applied,
                src == null ? null : src.toString(), lag, projector.state().name(), diag.lastFailureClass(),
                fa == null ? null : fa.toString(), circuitState.get(), ra == null ? null : ra.toString(), diag.counters());
    }

    @Override
    public ReconcileOutcome reconcile(String profileIdText, boolean acceptSourceRestore) {
        ProfileId p;
        try {
            p = ProfileId.parse(profileIdText);
        } catch (IllegalArgumentException e) {
            return new ReconcileOutcome("INVALID", null, "not a canonical profile id");
        }
        if (!p.equals(configuredProfile)) {
            return new ReconcileOutcome("FORBIDDEN", null, "profile not served by this installation");
        }
        try {
            ReconcileResult r = projector.reconcileNow(acceptSourceRestore, Duration.ofSeconds(60));
            return new ReconcileOutcome(r.kind().name(), r.appliedSequence() == null ? null : r.appliedSequence().toString(), "ok");
        } catch (Exception e) {
            return new ReconcileOutcome("FAILED", null, e.getClass().getSimpleName());
        }
    }
}
