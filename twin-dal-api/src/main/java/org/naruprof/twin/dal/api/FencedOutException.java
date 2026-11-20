package org.naruprof.twin.dal.api;

/** The caller's lease is no longer current: another writer owns the profile. Stop writing. */
public class FencedOutException extends RuntimeException {
    public FencedOutException(String message) {
        super(message);
    }
}
