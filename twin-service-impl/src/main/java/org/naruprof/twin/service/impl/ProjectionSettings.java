package org.naruprof.twin.service.impl;

import java.time.Duration;

/**
 * @param writerId          stable id of this installation's writer; the lease owner is writerId/instance
 * @param maxWriteAttempts  bound on reread/decide/write cycles (412, unknown outcome)
 * @param queueCapacity     bounded per-profile consumer queue; overflow = saturated stream
 */
public record ProjectionSettings(String writerId, int maxWriteAttempts, int queueCapacity,
                                 Duration backoffBase, Duration backoffCap, Duration actionableFailureDelay,
                                 Duration leaseTtl, Duration reconcileInterval, Duration drainTimeout) {

    public static ProjectionSettings defaults(String writerId) {
        return new ProjectionSettings(writerId, 5, 64, Duration.ofMillis(500), Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(10));
    }
}
