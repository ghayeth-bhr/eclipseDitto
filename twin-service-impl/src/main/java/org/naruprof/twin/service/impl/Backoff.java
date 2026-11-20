package org.naruprof.twin.service.impl;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Capped exponential back-off with full jitter. */
final class Backoff {

    private final Duration base;
    private final Duration cap;
    private int attempt;

    Backoff(Duration base, Duration cap) {
        this.base = base;
        this.cap = cap;
    }

    Duration next() {
        long ceiling = Math.min(cap.toMillis(), base.toMillis() * (1L << Math.min(attempt, 20)));
        attempt++;
        return Duration.ofMillis(Math.max(base.toMillis() / 2, ThreadLocalRandom.current().nextLong(ceiling + 1)));
    }

    void reset() {
        attempt = 0;
    }

    int attempts() {
        return attempt;
    }
}
