package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.ProfileId;

import java.time.Duration;
import java.util.Optional;

public interface LeaseStore {

    /** Acquire (or re-acquire) if free/expired/already ours. Takeover increments the token. */
    Optional<Lease> tryAcquire(ProfileId profile, String owner, Duration ttl);

    /** Extend; throws {@link FencedOutException} if lost. */
    Lease renew(Lease lease, Duration ttl);

    /** Throws {@link FencedOutException} unless {@code lease} is still the current, unexpired holder. */
    void assertHeld(Lease lease);

    void release(Lease lease);
}
