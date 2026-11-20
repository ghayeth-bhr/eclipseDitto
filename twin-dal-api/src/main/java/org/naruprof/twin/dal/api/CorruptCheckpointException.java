package org.naruprof.twin.dal.api;

public class CorruptCheckpointException extends RuntimeException {
    public CorruptCheckpointException(String message) {
        super(message);
    }
}
