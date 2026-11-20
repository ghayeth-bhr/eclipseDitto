package org.naruprof.twin.dal.narugrpc;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;

import java.util.concurrent.Executor;

/** Adds lowercase {@code authorization: Bearer <TWIN_TO_NARU_TOKEN>} metadata. Never logged. */
public final class BearerCallCredentials extends CallCredentials {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String headerValue;

    public BearerCallCredentials(char[] token) {
        if (token == null || token.length == 0) {
            throw new IllegalArgumentException("TWIN_TO_NARU_TOKEN is not configured");
        }
        this.headerValue = "Bearer " + new String(token);
    }

    @Override
    public void applyRequestMetadata(RequestInfo info, Executor executor, MetadataApplier applier) {
        try {
            Metadata m = new Metadata();
            m.put(AUTHORIZATION, headerValue);
            applier.apply(m);
        } catch (RuntimeException e) {
            applier.fail(Status.UNAUTHENTICATED.withDescription("credential unavailable"));
        }
    }

    @Override
    public String toString() {
        return "BearerCallCredentials[redacted]";
    }
}
