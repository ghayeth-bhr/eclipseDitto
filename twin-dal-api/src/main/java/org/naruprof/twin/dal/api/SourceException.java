package org.naruprof.twin.dal.api;

/** Classified failure of the NARU source. */
public class SourceException extends RuntimeException {

    public enum Kind {
        /** Transient: UNAVAILABLE, deadline exceeded, stream reset. Reconnect with back-off. */
        TRANSIENT,
        /** Cursor expired or ahead of head: take a fresh snapshot. */
        OUT_OF_RANGE,
        /** Unknown profile: investigate; never treat as deletion. */
        NOT_FOUND,
        /** UNAUTHENTICATED / PERMISSION_DENIED: actionable credential problem. */
        AUTH,
        /** Message violates the contract (profile mismatch, unset oneof, ...). */
        INCOMPATIBLE,
        /** Anything else. */
        OTHER
    }

    private final Kind kind;

    public SourceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public SourceException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
