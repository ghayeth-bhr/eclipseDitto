package org.naruprof.twin.service.api;

/**
 * Published facade of the learning twin module. Incoming adapters (REST) and other modules use
 * only this contract; projection internals, Ditto and the checkpoint store stay private.
 */
public interface LearningTwinModule {

    /**
     * Reads the applied twin of {@code profileIdText} through the read-only Ditto identity.
     * Never invents state: when nothing is confirmed the result is {@link SummaryResult.Unavailable}.
     */
    SummaryResult readSummary(String profileIdText);

    /** Process liveness (no I/O). */
    boolean isLive();

    /** Readiness: authenticated native read succeeded and projection initialized. */
    Readiness readiness();

    /** Private projection status for health/metrics: revisions, lag, failure class. No record content. */
    ProjectionStatusView status();

    /**
     * Explicit operator reconciliation against a fresh authoritative snapshot.
     *
     * @param acceptSourceRestore when true, a source that moved backwards is accepted as the new
     *                            truth (disaster restore); otherwise such a conflict is only reported.
     */
    ReconcileOutcome reconcile(String profileIdText, boolean acceptSourceRestore);
}
