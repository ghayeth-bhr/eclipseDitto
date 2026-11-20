package org.naruprof.twin.dal.api;

import java.time.Duration;
import java.util.Optional;

/** Native twin store failure, classified so callers can choose retry, back-off or alert. */
public class TwinStoreException extends RuntimeException {

    public enum Kind {
        /** Network failure, 5xx, gateway/backing service unavailable, or circuit open. */
        UNAVAILABLE,
        /** 429 or Ditto "too many requests": honor retryAfter. */
        THROTTLED,
        /** Request may or may not have been applied (timeout after send): read back. */
        UNKNOWN_OUTCOME,
        /** 401 for our credential: actionable, do not retry quickly. */
        UNAUTHENTICATED,
        /** 403 for an operation we expect to be allowed: actionable. */
        FORBIDDEN,
        /** The entity exists but is not ours or has an incompatible shape. */
        CONFLICT,
        /** Unexpected response schema/status. */
        INCOMPATIBLE
    }

    private final Kind kind;
    private final Duration retryAfter;

    public TwinStoreException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public TwinStoreException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
