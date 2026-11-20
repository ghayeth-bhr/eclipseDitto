package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.TwinPolicyStore;
import org.naruprof.twin.dal.api.TwinReadModel;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinThingStore;
import org.naruprof.twin.dal.api.WriteOutcome;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.TwinIds;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * In-memory stand-in for the three Ditto identities with native revisions/ETags, Ditto-like
 * existence hiding and fault injection. Used only to unit-test services against ports.
 */
class InMemoryDitto {

    static final class ThingRec {
        String thingId;
        String policyId;
        long revision;
        Map<String, Object> props;
        Set<String> otherFeatures = Set.of();
    }

    static final class PolicyRec {
        TwinPolicyStore.PolicyDocument doc;
        long revision;
    }

    final Map<String, ThingRec> things = new HashMap<>();
    final Map<String, PolicyRec> policies = new HashMap<>();
    int thingWrites;
    int policyWrites;
    volatile boolean down;
    /** When set, projector reads block until released (keeps the single worker busy). */
    volatile java.util.concurrent.CountDownLatch gate;
    /** Faults consumed by the next write (thing or policy). */
    final Deque<String> faults = new ArrayDeque<>();
    /** Runs right before the next thing replace is evaluated (simulates a concurrent writer). */
    Runnable beforeNextReplace;

    void fault(String f) {
        faults.add(f);
    }

    private void checkUp() {
        if (down) {
            throw new TwinStoreException(TwinStoreException.Kind.UNAVAILABLE, "down");
        }
    }

    /** "unavailable" = not applied; "timeout_after_apply" = applied then unknown outcome. */
    private <T> T write(Supplier<T> apply) {
        checkUp();
        String f = faults.poll();
        if ("unavailable".equals(f)) {
            throw new TwinStoreException(TwinStoreException.Kind.UNAVAILABLE, "injected");
        }
        T r = apply.get();
        if ("timeout_after_apply".equals(f)) {
            throw new TwinStoreException(TwinStoreException.Kind.UNKNOWN_OUTCOME, "injected");
        }
        return r;
    }

    // ---------------------------------------------------------------- projector

    final TwinThingStore projector = new TwinThingStore() {
        @Override
        public ThingRead read(ProfileId profile) {
            java.util.concurrent.CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            checkUp();
            ThingRec t = things.get(TwinIds.thingId(profile));
            if (t == null || !policies.containsKey(t.policyId)) {
                return new ThingRead.Absent(); // existence hiding: no policy => no access
            }
            return new ThingRead.Present("\"rev:" + t.revision + "\"", t.thingId, t.policyId,
                    Optional.ofNullable(t.props == null ? null : new LinkedHashMap<>(t.props)), t.otherFeatures, Map.of());
        }

        @Override
        public WriteOutcome create(ProfileId profile, String policyId, Map<String, String> props) {
            return write(() -> {
                String id = TwinIds.thingId(profile);
                if (things.containsKey(id)) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                if (!policies.containsKey(policyId)) {
                    throw new TwinStoreException(TwinStoreException.Kind.CONFLICT, "policy not visible");
                }
                ThingRec t = new ThingRec();
                t.thingId = id;
                t.policyId = policyId;
                t.revision = 1;
                t.props = new LinkedHashMap<>(props);
                things.put(id, t);
                thingWrites++;
                return WriteOutcome.CREATED;
            });
        }

        @Override
        public WriteOutcome replace(ProfileId profile, ThingRead.Present base, Map<String, String> props) {
            if (beforeNextReplace != null) {
                Runnable r = beforeNextReplace;
                beforeNextReplace = null;
                r.run();
            }
            return write(() -> {
                ThingRec t = things.get(TwinIds.thingId(profile));
                if (t == null) {
                    return WriteOutcome.NOT_FOUND;
                }
                if (!base.etag().equals("\"rev:" + t.revision + "\"")) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                t.props = new LinkedHashMap<>(props);
                t.otherFeatures = Set.of();
                t.revision++;
                thingWrites++;
                return WriteOutcome.APPLIED;
            });
        }

        @Override
        public WriteOutcome delete(ProfileId profile, String etag) {
            return write(() -> {
                ThingRec t = things.get(TwinIds.thingId(profile));
                if (t == null) {
                    return WriteOutcome.NOT_FOUND;
                }
                if (!etag.equals("\"rev:" + t.revision + "\"")) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                things.remove(t.thingId);
                thingWrites++;
                return WriteOutcome.APPLIED;
            });
        }
    };

    // ---------------------------------------------------------------- operator

    final TwinPolicyStore operator = new TwinPolicyStore() {
        @Override
        public PolicyRead read(String policyId) {
            checkUp();
            PolicyRec p = policies.get(policyId);
            return p == null ? new PolicyRead.Absent() : new PolicyRead.Present("\"rev:" + p.revision + "\"", p.doc);
        }

        @Override
        public WriteOutcome create(PolicyDocument policy) {
            return write(() -> {
                if (policies.containsKey(policy.policyId())) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                PolicyRec p = new PolicyRec();
                p.doc = policy;
                p.revision = 1;
                policies.put(policy.policyId(), p);
                policyWrites++;
                return WriteOutcome.CREATED;
            });
        }

        @Override
        public WriteOutcome replace(PolicyDocument policy, String etag) {
            return write(() -> {
                PolicyRec p = policies.get(policy.policyId());
                if (p == null) {
                    return WriteOutcome.NOT_FOUND;
                }
                if (!etag.equals("\"rev:" + p.revision + "\"")) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                p.doc = policy;
                p.revision++;
                policyWrites++;
                return WriteOutcome.APPLIED;
            });
        }

        @Override
        public WriteOutcome delete(String policyId, String etag) {
            return write(() -> {
                PolicyRec p = policies.get(policyId);
                if (p == null) {
                    return WriteOutcome.NOT_FOUND;
                }
                if (!etag.equals("\"rev:" + p.revision + "\"")) {
                    return WriteOutcome.PRECONDITION_FAILED;
                }
                policies.remove(policyId);
                policyWrites++;
                return WriteOutcome.APPLIED;
            });
        }
    };

    // ---------------------------------------------------------------- reader

    final TwinReadModel reader = new TwinReadModel() {
        @Override
        public LearningRead readLearning(ProfileId profile) {
            checkUp();
            ThingRec t = things.get(TwinIds.thingId(profile));
            if (t == null || !policies.containsKey(t.policyId) || t.props == null) {
                return new LearningRead.NotVisible();
            }
            return new LearningRead.Found(new LinkedHashMap<>(t.props));
        }

        @Override
        public void verifyAuthenticated() {
            checkUp();
        }
    };

    Map<String, Object> props(ProfileId p) {
        ThingRec t = things.get(TwinIds.thingId(p));
        return t == null ? null : t.props;
    }

    long revision(ProfileId p) {
        return things.get(TwinIds.thingId(p)).revision;
    }

    /** Simulates losing the Ditto volume. */
    void wipe() {
        things.clear();
        policies.clear();
    }
}
