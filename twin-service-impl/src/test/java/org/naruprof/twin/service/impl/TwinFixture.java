package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.infra.ProfileId;

import java.time.Clock;
import java.time.Duration;

/** Wires the real services against the in-memory ports. */
class TwinFixture {

    static final ProfileId P = ProfileId.parse("f7933216-20db-49cb-90e0-3a9d5396966d");
    static final String OP = "nginx:naruprof-twin-operator";
    static final String PR = "nginx:naruprof-twin-projector";
    static final String RD = "nginx:naruprof-twin-reader";

    final InMemoryDitto ditto = new InMemoryDitto();
    final InMemoryBookkeeping book = new InMemoryBookkeeping();
    final ScriptedSource source = new ScriptedSource(P);
    final ProjectionDiagnostics diag = new ProjectionDiagnostics(Clock.systemUTC());
    final TwinPolicyTemplate template = new TwinPolicyTemplate(OP, PR, RD);
    final ProvisioningService provisioning = new ProvisioningService(ditto.operator, template, book, diag, 5);
    final ProjectionService projection = new ProjectionService(ditto.projector, book, book, provisioning, diag, 5);
    final DeletionLifecycle deletion = new DeletionLifecycle(ditto.projector, book, book, provisioning, projection, diag, 5);
    final ReconcileService reconcile = new ReconcileService(source, book, projection, provisioning, deletion, diag);
    final SummaryReadService reads = new SummaryReadService(P, book, ditto.reader, diag);
    Lease lease = book.tryAcquire(P, "writer-a/1", Duration.ofMinutes(5)).orElseThrow();
}
