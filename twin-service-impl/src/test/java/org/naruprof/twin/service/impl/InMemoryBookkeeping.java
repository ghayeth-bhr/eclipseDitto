package org.naruprof.twin.service.impl;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.CorruptCheckpointException;
import org.naruprof.twin.dal.api.FencedOutException;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.U64;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory checkpoint + lease ports mirroring the JPA adapter's fencing rules. */
class InMemoryBookkeeping implements CheckpointStore, LeaseStore {

    final Map<ProfileId, Checkpoint> rows = new HashMap<>();
    final Map<ProfileId, Lease> leases = new HashMap<>();
    boolean corrupt;
    /** Simulates a crash: the next confirmApplied throws before committing. */
    boolean crashOnNextConfirm;
    int confirms;

    // ------------------------------------------------------------ lease

    @Override
    public synchronized Optional<Lease> tryAcquire(ProfileId p, String owner, Duration ttl) {
        Lease cur = leases.get(p);
        Instant exp = Instant.now().plus(ttl);
        if (cur == null) {
            Lease l = new Lease(p, owner, 1, exp);
            leases.put(p, l);
            return Optional.of(l);
        }
        if (cur.owner().equals(owner)) {
            Lease l = new Lease(p, owner, cur.fencingToken(), exp);
            leases.put(p, l);
            return Optional.of(l);
        }
        Lease l = new Lease(p, owner, cur.fencingToken() + 1, exp); // takeover (tests force it)
        leases.put(p, l);
        return Optional.of(l);
    }

    @Override
    public synchronized Lease renew(Lease lease, Duration ttl) {
        assertHeld(lease);
        Lease l = new Lease(lease.profile(), lease.owner(), lease.fencingToken(), Instant.now().plus(ttl));
        leases.put(lease.profile(), l);
        return l;
    }

    @Override
    public synchronized void assertHeld(Lease lease) {
        Lease cur = leases.get(lease.profile());
        if (cur == null || !cur.owner().equals(lease.owner()) || cur.fencingToken() != lease.fencingToken()) {
            throw new FencedOutException("fenced");
        }
    }

    @Override
    public synchronized void release(Lease lease) {
    }

    // ------------------------------------------------------------ checkpoint

    @Override
    public synchronized Optional<Checkpoint> find(ProfileId p) {
        if (corrupt) {
            throw new CorruptCheckpointException("injected");
        }
        return Optional.ofNullable(rows.get(p));
    }

    private Checkpoint row(ProfileId p) {
        return rows.getOrDefault(p, new Checkpoint(p, CheckpointStatus.BOOTSTRAPPING, Optional.empty(),
                Optional.empty(), Optional.empty(), Instant.now()));
    }

    @Override
    public synchronized void ensureRow(ProfileId p, Lease lease) {
        assertHeld(lease);
        rows.putIfAbsent(p, row(p));
    }

    @Override
    public synchronized boolean confirmApplied(ProfileId p, U64 seq, Lease lease) {
        assertHeld(lease);
        if (crashOnNextConfirm) {
            crashOnNextConfirm = false;
            throw new IllegalStateException("simulated crash before SQL commit");
        }
        Checkpoint c = row(p);
        if (c.isRetired() || c.status() == CheckpointStatus.RESTORE_CONFLICT) {
            return false;
        }
        if (c.applied().isPresent()) {
            int cmp = seq.compareTo(c.applied().get());
            if (cmp < 0) {
                return false;
            }
            if (cmp == 0 && c.status() == CheckpointStatus.LIVE) {
                return true;
            }
        }
        confirms++;
        rows.put(p, new Checkpoint(p, CheckpointStatus.LIVE, Optional.of(seq), c.tombstoneSequence(), Optional.empty(), Instant.now()));
        return true;
    }

    @Override
    public synchronized void resetApplied(ProfileId p, U64 seq, Lease lease, String reason) {
        assertHeld(lease);
        rows.put(p, new Checkpoint(p, CheckpointStatus.LIVE, Optional.of(seq), Optional.empty(), Optional.empty(), Instant.now()));
    }

    @Override
    public synchronized void markPendingDeletion(ProfileId p, U64 tomb, Lease lease) {
        assertHeld(lease);
        Checkpoint c = row(p);
        if (c.status() == CheckpointStatus.DELETED) {
            return;
        }
        rows.put(p, new Checkpoint(p, CheckpointStatus.PENDING_DELETION, c.applied(), Optional.of(tomb), Optional.empty(), Instant.now()));
    }

    @Override
    public synchronized void markDeleted(ProfileId p, U64 tomb, Lease lease) {
        assertHeld(lease);
        rows.put(p, new Checkpoint(p, CheckpointStatus.DELETED, Optional.of(tomb), Optional.of(tomb), Optional.empty(), Instant.now()));
    }

    @Override
    public synchronized void markRestoreConflict(ProfileId p, U64 src, Lease lease) {
        assertHeld(lease);
        Checkpoint c = row(p);
        rows.put(p, new Checkpoint(p, CheckpointStatus.RESTORE_CONFLICT, c.applied(), c.tombstoneSequence(), Optional.of(src), Instant.now()));
    }

    @Override
    public synchronized void discardCorrupt(ProfileId p, Lease lease) {
        assertHeld(lease);
        corrupt = false;
        rows.remove(p);
    }
}
