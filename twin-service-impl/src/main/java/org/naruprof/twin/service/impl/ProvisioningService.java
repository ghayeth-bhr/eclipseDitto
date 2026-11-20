package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.TwinPolicyStore;
import org.naruprof.twin.dal.api.TwinPolicyStore.PolicyRead;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.naruprof.twin.dal.api.WriteOutcome;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinIds;

/**
 * Operator-side Policy lifecycle: conditional create, verify-before-adopt, repair of our own
 * drifted policy, and cleanup narrowed to policies positively identified as this module's.
 */
public class ProvisioningService {

    private final TwinPolicyStore policies;
    private final TwinPolicyTemplate template;
    private final LeaseStore leases;
    private final ProjectionDiagnostics diag;
    private final int maxAttempts;

    public ProvisioningService(TwinPolicyStore policies, TwinPolicyTemplate template, LeaseStore leases,
                               ProjectionDiagnostics diag, int maxAttempts) {
        this.policies = policies;
        this.template = template;
        this.leases = leases;
        this.diag = diag;
        this.maxAttempts = maxAttempts;
    }

    /** Ensures our dedicated Policy exists with the exact expected shape. */
    public void ensurePolicy(ProfileId profile, Lease lease) {
        String policyId = TwinIds.policyId(profile);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            leases.assertHeld(lease);
            PolicyRead r = policies.read(policyId);
            if (r instanceof PolicyRead.Absent) {
                WriteOutcome o;
                try {
                    o = policies.create(template.expected(profile));
                } catch (TwinStoreException e) {
                    if (e.kind() == Kind.UNKNOWN_OUTCOME) {
                        continue; // read back decides
                    }
                    throw e;
                }
                if (o == WriteOutcome.CREATED || o == WriteOutcome.APPLIED) {
                    diag.count("policy.created");
                    return;
                }
                if (o == WriteOutcome.PRECONDITION_FAILED) {
                    // exists but invisible to the operator => not ours
                    PolicyRead again = policies.read(policyId);
                    if (again instanceof PolicyRead.Absent) {
                        diag.diagnostic("policy_conflict", profile.value(), "policy exists but operator cannot read it");
                        throw new TwinStoreException(Kind.CONFLICT, "pre-existing policy is not administered by the operator");
                    }
                    continue;
                }
                continue;
            }
            PolicyRead.Present p = (PolicyRead.Present) r;
            if (template.matches(profile, p.policy())) {
                return;
            }
            if (!template.isOurs(profile, p.policy())) {
                diag.diagnostic("policy_conflict", profile.value(), "pre-existing policy has a foreign shape");
                throw new TwinStoreException(Kind.CONFLICT, "pre-existing policy is not this module's");
            }
            WriteOutcome o;
            try {
                o = policies.replace(template.expected(profile), p.etag());
            } catch (TwinStoreException e) {
                if (e.kind() == Kind.UNKNOWN_OUTCOME) {
                    continue;
                }
                throw e;
            }
            if (o == WriteOutcome.APPLIED) {
                diag.diagnostic("policy_repaired", profile.value(), "policy entries restored to the template");
                return;
            }
            diag.count("policy.precondition_retry");
        }
        throw new TwinStoreException(Kind.UNAVAILABLE, "policy provisioning retries exhausted");
    }

    /**
     * Deletes our dedicated Policy and verifies its absence. A policy that is not positively ours
     * is never deleted.
     */
    public void removePolicy(ProfileId profile, Lease lease) {
        String policyId = TwinIds.policyId(profile);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            leases.assertHeld(lease);
            PolicyRead r = policies.read(policyId);
            if (r instanceof PolicyRead.Absent) {
                return; // verified absent (to the administrator identity)
            }
            PolicyRead.Present p = (PolicyRead.Present) r;
            if (!template.isOurs(profile, p.policy())) {
                diag.diagnostic("policy_conflict", profile.value(), "refusing to delete a policy that is not ours");
                throw new TwinStoreException(Kind.CONFLICT, "policy is not this module's");
            }
            try {
                WriteOutcome o = policies.delete(policyId, p.etag());
                if (o == WriteOutcome.APPLIED) {
                    diag.count("policy.deleted");
                }
            } catch (TwinStoreException e) {
                if (e.kind() != Kind.UNKNOWN_OUTCOME) {
                    throw e;
                }
            }
        }
        throw new TwinStoreException(Kind.UNAVAILABLE, "policy removal retries exhausted");
    }
}
