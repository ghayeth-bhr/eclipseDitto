package org.naruprof.twin.dal.jpa.repo;

import org.naruprof.twin.dal.api.FencedOutException;
import org.naruprof.twin.dal.api.Lease;
import org.naruprof.twin.dal.api.LeaseStore;
import org.naruprof.twin.dal.jpa.entity.LeaseEntity;
import org.naruprof.twin.infra.ProfileId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fenced single-writer lease in the private SQL store. Owner strings are
 * {@code <installation-writer-id>/<process-instance>}: a restart of the same installation
 * takes over immediately with a NEW fencing token (fencing in-flight writes of the dead
 * process); a different live installation must wait for expiry.
 */
public class JpaLeaseStore implements LeaseStore {

    private final SpringDataLeaseRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JpaLeaseStore(SpringDataLeaseRepository repo, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Optional<Lease> tryAcquire(ProfileId profile, String owner, Duration ttl) {
        try {
            return tx.execute(s -> {
                Instant now = clock.instant();
                Optional<LeaseEntity> cur = repo.lockById(profile.value());
                if (cur.isEmpty()) {
                    LeaseEntity e = repo.saveAndFlush(new LeaseEntity(profile.value(), owner, 1L, now.plus(ttl)));
                    return Optional.of(toLease(profile, e));
                }
                LeaseEntity e = cur.get();
                boolean same = e.getOwner().equals(owner);
                boolean expired = !e.getExpiresAt().isAfter(now);
                boolean restartOfSameWriter = writerId(e.getOwner()).equals(writerId(owner));
                if (same) {
                    e.setExpiresAt(now.plus(ttl));
                    return Optional.of(toLease(profile, e));
                }
                if (expired || restartOfSameWriter) {
                    e.setOwner(owner);
                    e.setFencingToken(e.getFencingToken() + 1);
                    e.setExpiresAt(now.plus(ttl));
                    return Optional.of(toLease(profile, e));
                }
                return Optional.<Lease>empty();
            });
        } catch (DataIntegrityViolationException race) {
            return Optional.empty();
        }
    }

    static String writerId(String owner) {
        int i = owner.indexOf('/');
        return i < 0 ? owner : owner.substring(0, i);
    }

    @Override
    public Lease renew(Lease lease, Duration ttl) {
        return tx.execute(s -> {
            LeaseEntity e = lockHeld(lease);
            e.setExpiresAt(clock.instant().plus(ttl));
            return toLease(lease.profile(), e);
        });
    }

    @Override
    public void assertHeld(Lease lease) {
        tx.executeWithoutResult(s -> lockHeld(lease));
    }

    /** Must run inside a transaction; locks the lease row. */
    LeaseEntity lockHeld(Lease lease) {
        LeaseEntity e = repo.lockById(lease.profile().value())
                .orElseThrow(() -> new FencedOutException("lease row missing"));
        if (!e.getOwner().equals(lease.owner()) || e.getFencingToken() != lease.fencingToken()) {
            throw new FencedOutException("lease taken over (token " + e.getFencingToken() + ")");
        }
        if (!e.getExpiresAt().isAfter(clock.instant())) {
            throw new FencedOutException("lease expired");
        }
        return e;
    }

    @Override
    public void release(Lease lease) {
        tx.executeWithoutResult(s -> repo.lockById(lease.profile().value()).ifPresent(e -> {
            if (e.getOwner().equals(lease.owner()) && e.getFencingToken() == lease.fencingToken()) {
                e.setExpiresAt(clock.instant());
            }
        }));
    }

    private static Lease toLease(ProfileId p, LeaseEntity e) {
        return new Lease(p, e.getOwner(), e.getFencingToken(), e.getExpiresAt());
    }
}
