package org.naruprof.twin.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.naruprof.twin.app.TwinApplication;
import org.naruprof.twin.fixture.naru.BearerAuthInterceptor;
import org.naruprof.twin.fixture.naru.FakeLearningProjection;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shared harness for tests against the REAL pinned Ditto stack (deploy/ditto). Uses only the
 * test-only identities from deploy/ditto/nginx.htpasswd.
 */
final class DittoItSupport {

    static final String API = apiBase();
    static final String COMPOSE_DIR = System.getProperty("ditto.it.composeDir", "../deploy/ditto");
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    static final String VERTX_TOKEN = "test-only-vertx-to-twin-token-0001";
    static final String MAINT_TOKEN = "test-only-twin-maintenance-token-01";
    static final String NARU_TOKEN = "test-only-twin-to-naru-token-00001";

    enum Who {
        OPERATOR("naruprof-twin-operator", "test-only-operator-secret"),
        PROJECTOR("naruprof-twin-projector", "test-only-projector-secret"),
        READER("naruprof-twin-reader", "test-only-reader-secret"),
        READER_B("naruprof-twin-reader-b", "test-only-reader-b-secret"),
        ANONYMOUS(null, null);

        final String user;
        final String password;

        Who(String user, String password) {
            this.user = user;
            this.password = password;
        }

        String subject() {
            return "nginx:" + user;
        }
    }

    private DittoItSupport() {
    }

    private static String apiBase() {
        String v = System.getProperty("ditto.it.apiBase");
        if (v == null || v.isBlank() || v.startsWith("${") || "null".equals(v)) {
            v = System.getenv().getOrDefault("DITTO_API_BASE", "http://127.0.0.1:8090/api/2");
        }
        return v;
    }

    record Resp(int status, String etag, String body) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("not JSON: " + body);
            }
        }
    }

    static Resp ditto(Who who, String method, String path, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(10));
            if (who.user != null) {
                b.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((who.user + ":" + who.password).getBytes(StandardCharsets.UTF_8)));
            }
            if (headers != null) {
                headers.forEach(b::header);
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.headers().firstValue("ETag").orElse(null), r.body());
        } catch (Exception e) {
            throw new IllegalStateException("ditto call failed: " + e, e);
        }
    }

    static Resp ditto(Who who, String method, String path) {
        return ditto(who, method, path, null, null);
    }

    static Resp http(String method, String url, String bearer) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            if (bearer != null) {
                b.header("Authorization", "Bearer " + bearer);
            }
            b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), null, r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean dittoReachable() {
        try {
            return ditto(Who.READER, "GET", "/whoami").status() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void await(String what, Duration timeout, BooleanSupplier c) {
        long end = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (c.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // keep polling
            }
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static String thingPath(String profile) {
        return "/things/naruprof.learning:" + profile;
    }

    static String policyPath(String profile) {
        return "/policies/naruprof.learning:policy-" + profile;
    }

    /** Test-only expected policy body (same as TwinPolicyTemplate). */
    static String policyBody(Who reader) {
        return """
                {"entries":{
                 "naruprof-operator":{"subjects":{"nginx:naruprof-twin-operator":{"type":"naruprof twin operator"}},
                   "resources":{"policy:/":{"grant":["READ","WRITE"],"revoke":[]}},"importable":"never"},
                 "naruprof-projector":{"subjects":{"nginx:naruprof-twin-projector":{"type":"naruprof twin projector"}},
                   "resources":{"thing:/":{"grant":["READ","WRITE"],"revoke":[]},"policy:/":{"grant":["READ"],"revoke":[]}},"importable":"never"},
                 "naruprof-reader":{"subjects":{"%s":{"type":"naruprof twin reader"}},
                   "resources":{"thing:/features/learning/properties":{"grant":["READ"],"revoke":[]}},"importable":"never"}}}
                """.formatted(reader.subject());
    }

    /** Removes the profile's Thing and Policy from Ditto (disposable fixtures only). */
    static void purge(String profile) {
        Resp p = ditto(Who.OPERATOR, "GET", policyPath(profile));
        if (p.status() != 200) {
            // re-establish our policy so the projector can see/delete a hidden thing, then remove both
            ditto(Who.OPERATOR, "PUT", policyPath(profile), policyBody(Who.READER), Map.of("If-None-Match", "*"));
        }
        Resp t = ditto(Who.PROJECTOR, "GET", thingPath(profile));
        if (t.status() == 200) {
            ditto(Who.PROJECTOR, "DELETE", thingPath(profile));
        }
        ditto(Who.OPERATOR, "DELETE", policyPath(profile));
    }

    /** Proves Thing absence even though the policy is gone: temporarily restore access, look, remove again. */
    static boolean thingPhysicallyAbsent(String profile) {
        boolean recreated = ditto(Who.OPERATOR, "PUT", policyPath(profile), policyBody(Who.READER),
                Map.of("If-None-Match", "*")).status() == 201;
        int st = ditto(Who.PROJECTOR, "GET", thingPath(profile)).status();
        if (recreated) {
            ditto(Who.OPERATOR, "DELETE", policyPath(profile));
        }
        return st == 404;
    }

    static void compose(String... args) throws Exception {
        String[] cmd = new String[args.length + 4];
        cmd[0] = "docker";
        cmd[1] = "compose";
        cmd[2] = "-p";
        // isolated test stacks: -Dditto.it.project=<name> (with TWIN_STACK_ID set for its volumes)
        cmd[3] = System.getProperty("ditto.it.project", System.getenv().getOrDefault("DITTO_IT_PROJECT", "naruprof-ditto"));
        System.arraycopy(args, 0, cmd, 4, args.length);
        Process p = new ProcessBuilder(cmd).directory(new File(COMPOSE_DIR)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(180, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("docker compose failed: " + out);
        }
    }

    // ------------------------------------------------------------------ fake NARU + app

    static final class Naru implements AutoCloseable {
        final FakeLearningProjection fake = new FakeLearningProjection();
        final Server server;

        Naru() throws Exception {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .addService(ServerInterceptors.intercept(fake, new BearerAuthInterceptor(NARU_TOKEN)))
                    .build().start();
        }

        String target() {
            return "127.0.0.1:" + server.getPort();
        }

        @Override
        public void close() throws Exception {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static final class App implements AutoCloseable {
        final String profile;
        final Path dataDir;
        final Naru naru;
        ConfigurableApplicationContext ctx;
        final Map<String, Object> extra = new HashMap<>();

        App(String profile, Path dataDir, Naru naru) {
            this.profile = profile;
            this.dataDir = dataDir;
            this.naru = naru;
        }

        App start() {
            Map<String, Object> p = new HashMap<>();
            p.put("server.port", "0");
            p.put("twin.profile-id", profile);
            p.put("twin.data-dir", dataDir.toString().replace('\\', '/'));
            p.put("twin.inbound.vertx-token", VERTX_TOKEN);
            p.put("twin.inbound.maintenance-token", MAINT_TOKEN);
            p.put("twin.naru.mode", "fake"); // in-process fixture publisher on loopback
            p.put("twin.naru.target", naru.target());
            p.put("twin.naru.token", NARU_TOKEN);
            p.put("twin.ditto.api-base", API);
            p.put("twin.ditto.operator.password", Who.OPERATOR.password);
            p.put("twin.ditto.projector.password", Who.PROJECTOR.password);
            p.put("twin.ditto.reader.password", Who.READER.password);
            p.put("twin.ditto.breaker.open-duration", "2s");
            p.put("twin.projection.writer-id", "it-writer");
            p.put("twin.projection.backoff-base", "100ms");
            p.put("twin.projection.backoff-cap", "2s");
            p.put("twin.projection.actionable-failure-delay", "3s");
            p.put("twin.projection.lease-ttl", "10s");
            p.put("twin.projection.reconcile-interval", "1h");
            p.putAll(extra);
            // command-line args outrank application.yml (builder .properties() would only be defaults)
            String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
            ctx = new SpringApplicationBuilder(TwinApplication.class).run(args);
            return this;
        }

        String base() {
            return "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
        }

        Resp summary() {
            return http("GET", base() + "/internal/v1/profiles/" + profile + "/summary", VERTX_TOKEN);
        }

        String summarySequence() {
            Resp r = summary();
            return r.status() == 200 ? r.json().path("sequence").asText() : null;
        }

        Resp status() {
            return http("GET", base() + "/internal/v1/projection/status", MAINT_TOKEN);
        }

        void stop() {
            if (ctx != null) {
                ctx.close();
                ctx = null;
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    static String newProfile() {
        return UUID.randomUUID().toString();
    }

    static Path tempDir() throws Exception {
        return Files.createTempDirectory("naruprof-twin-it-");
    }
}
