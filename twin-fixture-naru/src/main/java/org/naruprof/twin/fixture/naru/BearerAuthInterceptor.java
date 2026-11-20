package org.naruprof.twin.fixture.naru;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Checks the lowercase {@code authorization} metadata against the configured peer credential. */
public class BearerAuthInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expected;

    public BearerAuthInterceptor(String token) {
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        String v = headers.get(AUTHORIZATION);
        if (v == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("missing credential"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        if (!MessageDigest.isEqual(expected, v.getBytes(StandardCharsets.UTF_8))) {
            call.close(Status.PERMISSION_DENIED.withDescription("wrong peer"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return next.startCall(call, headers);
    }
}
