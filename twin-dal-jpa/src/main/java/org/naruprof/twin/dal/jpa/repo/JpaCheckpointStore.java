package org.naruprof.twin.dal.jpa.repo;

import org.naruprof.twin.dal.api.CheckpointStore;
import org.naruprof.twin.dal.api.CorruptCheckpointException;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.jpa.entity.CheckpointEntity;
import org.naruprof.twin.infra.Checkpoint;
import org.naruprof.twin.infra.CheckpointStatus;
import org.naruprof.twin.infra.ProfileId;
import org.naruprof.twin.infra.U64;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;

/**
 * Checkpoint bookkeeping. Each mutation runs in one local transaction that first locks and
 * validates the caller's lease (fencing), then updates the profile row.
 */
public class JpaCheckpointStore implements CheckpointStore {

    private final SpringDataCheckpointRepository repo;
    private final JpaLeaseStore leases;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JpaCheckpointStore(SpringDataCheckpointRepository repo, JpaLeaseStore leases, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.leases = leases;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Optional<Checkpoint> find(ProfileId profile) {
        return repo.findById(profile.value()).map(e -> toModel(profile, e));
    }

    static Checkpoint toModel(ProfileId profile, CheckpointEntity e) {
        CheckpointStatus status;
        try {
            status = CheckpointStatus.valueOf(e.getStatus());
        } catch (RuntimeException ex) {
            throw new CorruptCheckpointException("unknown status");
        }
        Optional<U64> applied = seq(e.getAppliedSequence());
        Optional<U64> tomb = seq(e.getTombstoneSequence());
        Optional<U64> conflict = seq(e.getConflictSequence());
        if (status == CheckpointStatus.LIVE && applied.isEmpty()) {
            throw new CorruptCheckpointException("LIVE without applied sequence");
        }
        if ((status == CheckpointStatus.PENDING_DELETION || status == CheckpointStatus.DELETED) && tomb.isEmpty()) {
            throw new CorruptCheckpointException(status + " without tombstone sequence");
        }
        return new Checkpoint(profile, status, applied, tomb, conflict, e.getUpdatedAt());
    }

    private static Optional<U64> seq(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(U64.parse(text));
        } catch (IllegalArgumentException ex) {
            throw new CorruptCheckpointException("unparseable sequence");
        }
    }

    private CheckpointEntity row(ProfileId profile) {
        return repo.findById(profile.value())
                .orElseGet(() -> repo.save(new CheckpointEntity(profile.value(), CheckpointStatus.BOOTSTRAPPING.name(), clock.instant())));
    }

    private void touch(CheckpointEntity e, Lease lease) {
        e.setLastWriterToken(lease.fencingToken());
        e.setUpdatedAt(clock.instant());
    }

    @Override
    public void ensureRow(ProfileId profile, Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            if (repo.findById(profile.value()).isEmpty()) {
                CheckpointEntity e = new CheckpointEntity(profile.value(), CheckpointStatus.BOOTSTRAPPING.name(), clock.instant());
                touch(e, lease);
                repo.save(e);
            }
        });
    }

    @Override
    public boolean confirmApplied(ProfileId profile, U64 sequence, Lease lease) {
        Boolean r = tx.execute(s -> {
            leases.lockHeld(lease);
            CheckpointEntity e = row(profile);
            Checkpoint cp = toModel(profile, e);
            if (cp.isRetired() || cp.status() == CheckpointStatus.RESTORE_CONFLICT) {
                return false;
            }
            if (cp.applied().isPresent()) {
                int c = sequence.compareTo(cp.applied().get());
                if (c < 0) {
                    return false;
                }
                if (c == 0 && cp.status() == CheckpointStatus.LIVE) {
                    return true;
                }
            }
            e.setAppliedSequence(sequence.toString());
            e.setStatus(CheckpointStatus.LIVE.name());
            touch(e, lease);
            return true;
        });
        return Boolean.TRUE.equals(r);
    }

    @Override
    public void resetApplied(ProfileId profile, U64 sequence, Lease lease, String reason) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            CheckpointEntity e = row(profile);
            e.setAppliedSequence(sequence.toString());
            e.setStatus(CheckpointStatus.LIVE.name());
            e.setConflictSequence(null);
            touch(e, lease);
        });
    }

    @Override
    public void markPendingDeletion(ProfileId profile, U64 tombstoneSequence, Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            CheckpointEntity e = row(profile);
            if (CheckpointStatus.DELETED.name().equals(e.getStatus())) {
                return; // already fully applied; tombstone retained
            }
            e.setStatus(CheckpointStatus.PENDING_DELETION.name());
            e.setTombstoneSequence(tombstoneSequence.toString());
            touch(e, lease);
        });
    }

    @Override
    public void markDeleted(ProfileId profile, U64 tombstoneSequence, Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            CheckpointEntity e = row(profile);
            e.setStatus(CheckpointStatus.DELETED.name());
            e.setTombstoneSequence(tombstoneSequence.toString());
            e.setAppliedSequence(tombstoneSequence.toString());
            touch(e, lease);
        });
    }

    @Override
    public void markRestoreConflict(ProfileId profile, U64 sourceSequence, Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            CheckpointEntity e = row(profile);
            e.setStatus(CheckpointStatus.RESTORE_CONFLICT.name());
            e.setConflictSequence(sourceSequence.toString());
            touch(e, lease);
        });
    }

    @Override
    public void discardCorrupt(ProfileId profile, Lease lease) {
        tx.executeWithoutResult(s -> {
            leases.lockHeld(lease);
            repo.deleteById(profile.value());
        });
    }
}
