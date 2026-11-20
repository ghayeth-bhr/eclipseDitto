package org.naruprof.twin.service.impl;

import org.junit.jupiter.api.Test;
import org.naruprof.twin.dal.api.FencedOutException;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.TwinPolicyStore;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.LearningSummary;
import org.naruprof.twin.infra.SourceState;
import org.naruprof.twin.infra.TwinIds;
import org.naruprof.twin.infra.TwinSummaryView;
import org.naruprof.twin.infra.U64;
import org.naruprof.twin.service.api.SummaryResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.naruprof.twin.service.impl.TwinFixture.P;

/** Projection service against ports only (no Ditto, no SQL, no gRPC). */
class ProjectionServiceTest {

    final TwinFixture f = new TwinFixture();

    private static Map<String, Object> props(long seq, long t, long p, long props, long chains) {
        return Map.of("sequence", "" + seq, "teachingSessions", "" + t, "practiceSessions", "" + p,
                "propositions", "" + props, "reasoningChains", "" + chains);
    }

    private ApplyResult apply(SourceState s) {
        return f.projection.applyLive((SourceState.Live) s, f.lease, ProjectionService.Mode.NORMAL);
    }

    private void commonTraceApplied() {
        f.reconcile.reconcile(P, f.lease, false); // bootstrap at 0
        apply(f.source.commit(1, 0, 0, 0));
        apply(f.source.commit(1, 1, 0, 0));
        apply(f.source.commit(1, 1, 2, 1));
    }

    @Test
    void emptyProfileCreatesMeaningfulZeroTwinWithExplicitPolicy() {
        ReconcileResult r = f.reconcile.reconcile(P, f.lease, false);
        assertThat(r.kind()).isEqualTo(ReconcileResult.Kind.LIVE);
        assertThat(f.ditto.props(P)).isEqualTo(props(0, 0, 0, 0, 0));
        assertThat(f.ditto.things.get(TwinIds.thingId(P)).policyId).isEqualTo(TwinIds.policyId(P));
        assertThat(f.template.matches(P, f.ditto.policies.get(TwinIds.policyId(P)).doc)).isTrue();
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.ZERO);
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Found(new TwinSummaryView(P.value(), "0", "0", "0", "0", "0")));
    }

    @Test
    void commonTraceProducesExactContractValues() {
        commonTraceApplied();
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Found(
                new TwinSummaryView("f7933216-20db-49cb-90e0-3a9d5396966d", "3", "1", "1", "2", "1")));
    }

    @Test
    void duplicateAndLateReplayNeverRollBack() {
        commonTraceApplied();
        long rev = f.ditto.revision(P);
        assertThat(apply(f.source.live(3))).isEqualTo(ApplyResult.ALREADY_APPLIED);
        assertThat(apply(f.source.live(2))).isEqualTo(ApplyResult.IGNORED_STALE);
        assertThat(f.ditto.revision(P)).isEqualTo(rev);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(3));
    }

    @Test
    void staleEtagCausesRereadAndOrderingDecisionAgain() {
        f.reconcile.reconcile(P, f.lease, false);
        // a concurrent writer (e.g. an old process) bumps the native revision just before our write
        f.ditto.beforeNextReplace = () -> f.ditto.things.get(TwinIds.thingId(P)).revision++;
        assertThat(apply(f.source.commit(1, 0, 0, 0))).isEqualTo(ApplyResult.APPLIED);
        assertThat(f.diag.counter("write.precondition_retry")).isEqualTo(1);
        assertThat(f.ditto.props(P)).isEqualTo(props(1, 1, 0, 0, 0));
    }

    @Test
    void concurrentNewerWriteWinsAndOlderEventIsIgnoredAfter412() {
        f.reconcile.reconcile(P, f.lease, false);
        SourceState s1 = f.source.commit(1, 0, 0, 0);
        SourceState s2 = f.source.commit(1, 1, 0, 0);
        // between our read and write, another writer applies revision 2
        f.ditto.beforeNextReplace = () -> {
            InMemoryDitto.ThingRec t = f.ditto.things.get(TwinIds.thingId(P));
            t.props = new java.util.LinkedHashMap<>(LearningFeatureCodec.encode(((SourceState.Live) s2).toTwinState()));
            t.revision++;
        };
        assertThat(apply(s1)).isEqualTo(ApplyResult.IGNORED_STALE);
        assertThat(f.ditto.props(P)).isEqualTo(props(2, 1, 1, 0, 0));
    }

    @Test
    void timeoutAfterWriteIsResolvedByReadingBack() {
        f.reconcile.reconcile(P, f.lease, false);
        f.ditto.fault("timeout_after_apply");
        assertThat(apply(f.source.commit(1, 0, 0, 0))).isEqualTo(ApplyResult.ALREADY_APPLIED);
        assertThat(f.ditto.thingWrites).isEqualTo(2); // create + one replace, no duplicate write
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(1));
    }

    @Test
    void crashAfterNativeWriteBeforeCheckpointAdvancesOnceOnRestart() {
        commonTraceApplied();
        SourceState s4 = f.source.commit(2, 1, 2, 1);
        f.book.crashOnNextConfirm = true;
        assertThatThrownBy(() -> apply(s4)).hasMessageContaining("simulated crash");
        assertThat(f.ditto.props(P)).isEqualTo(props(4, 2, 1, 2, 1)); // native write landed
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(3)); // cursor not advanced
        int writes = f.ditto.thingWrites;
        int confirms = f.book.confirms;
        // restart: new process instance takes over the lease and reconciles from the snapshot
        Lease restarted = f.book.tryAcquire(P, "writer-a/2", Duration.ofMinutes(1)).orElseThrow();
        ReconcileResult r = f.reconcile.reconcile(P, restarted, false);
        assertThat(r.appliedSequence()).isEqualTo(U64.of(4));
        assertThat(f.ditto.thingWrites).isEqualTo(writes); // recognized as already applied
        assertThat(f.book.confirms).isEqualTo(confirms + 1); // advanced exactly once
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(4));
    }

    @Test
    void equalSequenceWithDifferentContentIsDriftAndSnapshotRepairsIt() {
        commonTraceApplied();
        SourceState.Live bogus = new SourceState.Live(P, U64.of(3), LearningSummary.of(9, 9, 9, 9));
        assertThat(apply(bogus)).isEqualTo(ApplyResult.DRIFT);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
        // now corrupt the native twin at the same version and reconcile from the authoritative snapshot
        f.ditto.things.get(TwinIds.thingId(P)).props.put("propositions", "7");
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
        assertThat(f.diag.counter("diagnostic.twin_repaired")).isEqualTo(1);
    }

    @Test
    void learnerRatingsOrOtherExtraPropertiesAreNeverPreserved() {
        commonTraceApplied();
        f.ditto.things.get(TwinIds.thingId(P)).props.put("rating", "5");
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.ditto.props(P)).doesNotContainKey("rating").isEqualTo(props(3, 1, 1, 2, 1));
    }

    @Test
    void lostDittoStoreWithIntactCursorIsRebuilt() {
        commonTraceApplied();
        f.ditto.wipe();
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
        assertThat(f.ditto.policies).containsKey(TwinIds.policyId(P));
        assertThat(f.diag.counter("diagnostic.twin_rebuilt")).isEqualTo(1);
    }

    @Test
    void lostCheckpointStoreWithIntactThingIsAdoptedWithoutWriting() {
        commonTraceApplied();
        f.book.rows.clear();
        int writes = f.ditto.thingWrites;
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.ditto.thingWrites).isEqualTo(writes);
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(3));
    }

    @Test
    void corruptCheckpointIsDiscardedAndRebuiltFromSnapshot() {
        commonTraceApplied();
        f.book.corrupt = true;
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.book.find(P).orElseThrow().applied()).contains(U64.of(3));
        assertThat(f.diag.counter("diagnostic.corrupt_checkpoint")).isEqualTo(1);
    }

    @Test
    void sourceMovingBackwardsIsARestoreConflictUntilExplicitlyAccepted() {
        commonTraceApplied();
        f.source.rewindTo(2);
        ReconcileResult r = f.reconcile.reconcile(P, f.lease, false);
        assertThat(r.kind()).isEqualTo(ReconcileResult.Kind.RESTORE_CONFLICT);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1)); // never rewound implicitly
        assertThat(f.book.find(P).orElseThrow().status()).isEqualTo(CheckpointStatus.RESTORE_CONFLICT);
        // still serves the applied revision
        assertThat(f.reads.read(P.value())).isInstanceOf(SummaryResult.Found.class);
        // streamed events are refused while in conflict
        assertThat(apply(f.source.commit(1, 1, 3, 1))).isEqualTo(ApplyResult.CONFLICT_PENDING);
        f.source.rewindTo(2);
        ReconcileResult accepted = f.reconcile.reconcile(P, f.lease, true);
        assertThat(accepted.kind()).isEqualTo(ReconcileResult.Kind.RESTORED);
        assertThat(f.ditto.props(P)).isEqualTo(props(2, 1, 1, 0, 0));
        assertThat(f.book.find(P).orElseThrow().status()).isEqualTo(CheckpointStatus.LIVE);
    }

    @Test
    void disasterRestoreWithCheckpointAheadOfThingConverges() {
        commonTraceApplied();
        // Ditto restored from an older backup (thing at 1) while SQL says 3 and source is at 3
        f.ditto.things.get(TwinIds.thingId(P)).props = new java.util.LinkedHashMap<>(props(1, 1, 0, 0, 0));
        ReconcileResult r = f.reconcile.reconcile(P, f.lease, false);
        assertThat(r.kind()).isEqualTo(ReconcileResult.Kind.LIVE);
        assertThat(f.ditto.props(P)).isEqualTo(props(3, 1, 1, 2, 1));
    }

    @Test
    void deletionRemovesThingThenPolicyAndRetainsTombstone() {
        commonTraceApplied();
        SourceState.Tombstone t = (SourceState.Tombstone) f.source.delete();
        f.deletion.begin(t, f.lease);
        assertThat(f.ditto.things).isEmpty();
        assertThat(f.ditto.policies).isEmpty();
        assertThat(f.book.find(P).orElseThrow().status()).isEqualTo(CheckpointStatus.DELETED);
        assertThat(f.book.find(P).orElseThrow().tombstoneSequence()).contains(U64.of(4));
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Missing());
        // a delayed create/update for the retired id is rejected: no resurrection
        assertThat(apply(new SourceState.Live(P, U64.of(5), LearningSummary.of(1, 1, 1, 1)))).isEqualTo(ApplyResult.RETIRED_IGNORED);
        assertThat(f.ditto.things).isEmpty();
    }

    @Test
    void interruptedDeletionResumesAfterRestart() {
        commonTraceApplied();
        SourceState.Tombstone t = (SourceState.Tombstone) f.source.delete();
        f.ditto.fault("ok"); // thing delete ok
        f.ditto.fault("unavailable"); // policy delete fails
        assertThatThrownBy(() -> f.deletion.begin(t, f.lease)).isInstanceOf(TwinStoreException.class);
        assertThat(f.book.find(P).orElseThrow().status()).isEqualTo(CheckpointStatus.PENDING_DELETION);
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Missing());
        Lease restarted = f.book.tryAcquire(P, "writer-a/2", Duration.ofMinutes(1)).orElseThrow();
        f.reconcile.reconcile(P, restarted, false); // startup recheck: source says tombstone
        assertThat(f.ditto.policies).isEmpty();
        assertThat(f.book.find(P).orElseThrow().status()).isEqualTo(CheckpointStatus.DELETED);
    }

    @Test
    void reconcileRemovesResurrectedThingAndOrphanPolicyOfRetiredProfile() {
        commonTraceApplied();
        f.deletion.begin((SourceState.Tombstone) f.source.delete(), f.lease);
        // a zombie writer re-created the policy (failed create left it orphaned)
        f.ditto.operator.create(f.template.expected(P));
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.ditto.policies).isEmpty();
    }

    @Test
    void fencedOutWriterCannotWriteOrAdvanceCursor() {
        f.reconcile.reconcile(P, f.lease, false);
        Lease old = f.lease;
        f.book.tryAcquire(P, "writer-b/1", Duration.ofMinutes(1)).orElseThrow(); // takeover
        int writes = f.ditto.thingWrites;
        assertThatThrownBy(() -> f.projection.applyLive((SourceState.Live) f.source.commit(1, 0, 0, 0), old,
                ProjectionService.Mode.NORMAL)).isInstanceOf(FencedOutException.class);
        assertThat(f.ditto.thingWrites).isEqualTo(writes);
    }

    @Test
    void foreignPreExistingPolicyIsAConflictNotAdopted() {
        f.ditto.policies.put(TwinIds.policyId(P), foreignPolicy());
        assertThatThrownBy(() -> f.reconcile.reconcile(P, f.lease, false))
                .isInstanceOf(TwinStoreException.class)
                .extracting(e -> ((TwinStoreException) e).kind()).isEqualTo(TwinStoreException.Kind.CONFLICT);
        assertThat(f.ditto.things).isEmpty();
    }

    @Test
    void ourDriftedPolicyIsRepaired() {
        f.reconcile.reconcile(P, f.lease, false);
        InMemoryDitto.PolicyRec rec = f.ditto.policies.get(TwinIds.policyId(P));
        java.util.Map<String, TwinPolicyStore.Entry> entries = new java.util.LinkedHashMap<>(rec.doc.entries());
        entries.put(TwinPolicyTemplate.READER_LABEL, new TwinPolicyStore.Entry(Map.of(TwinFixture.RD, "x"),
                Map.of("thing:/", new TwinPolicyStore.Permissions(java.util.Set.of("READ", "WRITE"), java.util.Set.of()))));
        rec.doc = new TwinPolicyStore.PolicyDocument(rec.doc.policyId(), entries);
        f.reconcile.reconcile(P, f.lease, false);
        assertThat(f.template.matches(P, f.ditto.policies.get(TwinIds.policyId(P)).doc)).isTrue();
        assertThat(f.diag.counter("diagnostic.policy_repaired")).isEqualTo(1);
    }

    @Test
    void thingBoundToAnotherPolicyIsAConflict() {
        f.reconcile.reconcile(P, f.lease, false);
        String other = "naruprof.learning:policy-00000000-0000-4000-8000-000000000000";
        f.ditto.operator.create(new TwinPolicyStore.PolicyDocument(other, Map.of()));
        f.ditto.things.get(TwinIds.thingId(P)).policyId = other;
        assertThatThrownBy(() -> apply(f.source.commit(1, 0, 0, 0))).isInstanceOf(TwinStoreException.class);
    }

    @Test
    void readPathNeverInventsZeros() {
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Unavailable("bootstrapping"));
        f.reconcile.reconcile(P, f.lease, false);
        f.ditto.wipe(); // Ditto lost its volume: confirmed state exists but the twin is gone
        assertThat(f.reads.read(P.value())).isInstanceOf(SummaryResult.Unavailable.class);
        f.ditto.down = true;
        assertThat(f.reads.read(P.value())).isEqualTo(new SummaryResult.Unavailable("ditto_unavailable"));
        assertThat(f.reads.read("00000000-0000-4000-8000-000000000000")).isEqualTo(new SummaryResult.Forbidden());
        assertThat(f.reads.read("not-a-uuid")).isEqualTo(new SummaryResult.Invalid());
        assertThat(f.reads.read(P.value().toUpperCase())).isNotInstanceOf(SummaryResult.Forbidden.class);
    }

    private static InMemoryDitto.PolicyRec foreignPolicy() {
        InMemoryDitto.PolicyRec r = new InMemoryDitto.PolicyRec();
        r.doc = new TwinPolicyStore.PolicyDocument(TwinIds.policyId(P), Map.of("admin",
                new TwinPolicyStore.Entry(Map.of("nginx:someone-else", "x"),
                        Map.of("policy:/", new TwinPolicyStore.Permissions(java.util.Set.of("READ", "WRITE"), java.util.Set.of())))));
        r.revision = 1;
        return r;
    }
}
