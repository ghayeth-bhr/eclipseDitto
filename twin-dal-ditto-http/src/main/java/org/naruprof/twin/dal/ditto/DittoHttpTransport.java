package org.naruprof.twin.dal.ditto;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.naruprof.twin.dal.api.TwinStoreException;
import org.naruprof.twin.dal.api.TwinStoreException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal HTTP transport to the persisted twin API under {@code /api/2}. Owns the native-HTTP
 * timeout policy and the circuit breaker shared by all Ditto I/O. Classifies failures into
 * {@link TwinStoreException.Kind}s; never logs credentials or bodies.
 */
public class DittoHttpTransport {

    private static final Logger log = LoggerFactory.getLogger(DittoHttpTransport.class);

    private final HttpClient client;
    private final String apiBase;
    private final Duration requestTimeout;
    private final CircuitBreaker breaker;

    public DittoHttpTransport(String apiBase, Duration connectTimeout, Duration requestTimeout, CircuitBreaker breaker) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        if (!this.apiBase.endsWith("/api/2")) {
            throw new IllegalArgumentException("DITTO_API_BASE must end with /api/2");
        }
        this.requestTimeout = requestTimeout;
        this.breaker = breaker;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public record Response(int status, Map<String, java.util.List<String>> headers, String body) {
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst();
        }
    }

    public CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    /**
     * @param path            path below /api/2, e.g. "/things/naruprof.learning:..."
     * @param conditionHeader e.g. "If-Match" or null
     */
    public Response send(DittoIdentity who, String method, String path, String jsonBody,
                         String conditionHeader, String conditionValue) {
        boolean mutating = !"GET".equals(method);
        long started = System.nanoTime();
        try {
            Response r = breaker.executeCheckedSupplier(() -> exchange(who, method, path, jsonBody,
                    conditionHeader, conditionValue, mutating));
            log.debug("ditto {} {} as {} -> {} in {}ms", method, path, who.role(), r.status(),
                    (System.nanoTime() - started) / 1_000_000);
            return r;
        } catch (CallNotPermittedException open) {
            throw new TwinStoreException(Kind.UNAVAILABLE, "ditto circuit breaker open", null, open);
        } catch (TwinStoreException e) {
            log.debug("ditto {} {} as {} failed: {} in {}ms", method, path, who.role(), e.kind(),
                    (System.nanoTime() - started) / 1_000_000);
            throw e;
        } catch (Throwable t) {
            throw new TwinStoreException(Kind.UNAVAILABLE, "ditto call failed: " + t.getClass().getSimpleName(), null, t);
        }
    }

    private Response exchange(DittoIdentity who, String method, String path, String jsonBody,
                              String conditionHeader, String conditionValue, boolean mutating) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(requestTimeout)
                .header("Authorization", who.authorizationHeader())
                .header("Accept", "application/json");
        if (conditionHeader != null) {
            b.header(conditionHeader, conditionValue);
        }
        HttpRequest.BodyPublisher body = jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body);
        HttpResponse<String> resp;
        try {
            resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // the request never reached Ditto: definitely not applied
            throw new TwinStoreException(Kind.UNAVAILABLE, "ditto connect failed", null, e);
        } catch (HttpTimeoutException e) {
            throw new TwinStoreException(mutating ? Kind.UNKNOWN_OUTCOME : Kind.UNAVAILABLE,
                    "ditto request timed out", null, e);
        } catch (IOException e) {
            throw new TwinStoreException(mutating ? Kind.UNKNOWN_OUTCOME : Kind.UNAVAILABLE,
                    "ditto I/O failure: " + e.getClass().getSimpleName(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwinStoreException(mutating ? Kind.UNKNOWN_OUTCOME : Kind.UNAVAILABLE, "interrupted", null, e);
        }
        Response r = new Response(resp.statusCode(), resp.headers().map(), resp.body());
        classifyTransportStatus(r, mutating);
        return r;
    }

    /** Throws for statuses that are never a business outcome; returns normally otherwise. */
    private static void classifyTransportStatus(Response r, boolean mutating) {
        int s = r.status();
        switch (s) {
            case 401 -> throw new TwinStoreException(Kind.UNAUTHENTICATED, "ditto rejected the credential (401)");
            case 429 -> throw new TwinStoreException(Kind.THROTTLED, "ditto throttled (429)", retryAfter(r), null);
            case 408, 504 -> throw new TwinStoreException(mutating ? Kind.UNKNOWN_OUTCOME : Kind.UNAVAILABLE,
                    "ditto timeout (" + s + ")", retryAfter(r), null);
            case 502, 503 -> throw new TwinStoreException(Kind.UNAVAILABLE, "ditto unavailable (" + s + ")", retryAfter(r), null);
            default -> {
                if (s >= 500) {
                    throw new TwinStoreException(mutating ? Kind.UNKNOWN_OUTCOME : Kind.UNAVAILABLE,
                            "ditto server error (" + s + ")");
                }
            }
        }
    }

    private static Duration retryAfter(Response r) {
        return r.header("Retry-After").map(v -> {
            try {
                return Duration.ofSeconds(Math.min(300, Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }
}
