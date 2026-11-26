package org.naruprof.twin.fixture.naru;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Checks the lowercase {@code authorization} metadata against the configured peer credential.
 * Mirrors current NARU: missing, wrong or repeated metadata is {@code UNAUTHENTICATED}.
 */
public class BearerAuthInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expected;

    public BearerAuthInterceptor(String token) {
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        Iterable<String> all = headers.getAll(AUTHORIZATION);
        java.util.List<String> values = new java.util.ArrayList<>();
        if (all != null) {
            all.forEach(values::add);
        }
        if (values.size() != 1 || !MessageDigest.isEqual(expected, values.get(0).getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.UNAUTHENTICATED.withDescription("credential rejected"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return next.startCall(call, headers);
    }
}
