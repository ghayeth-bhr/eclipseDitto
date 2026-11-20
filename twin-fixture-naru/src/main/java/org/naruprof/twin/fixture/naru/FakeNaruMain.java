package org.naruprof.twin.fixture.naru;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone fake NARU publisher for peer-independent runs.
 *
 * <pre>
 * env NARUPROF_PROFILE_ID   profile to serve (default: contract example id)
 *     TWIN_TO_NARU_TOKEN    expected bearer credential (required)
 *     FAKE_NARU_GRPC_PORT   default 9092
 *     FAKE_NARU_CONTROL_PORT loopback control API, default 9093
 *     FAKE_NARU_TRACE       "common" (0..3, default) or "empty" (revision 0 only)
 * control (127.0.0.1 only):
 *     GET  /head
 *     POST /commit?teaching=1&practice=1&propositions=2&chains=1
 *     POST /delete
 *     POST /rewind?to=2
 *     POST /available?value=false
 * </pre>
 */
public final class FakeNaruMain {

    private static final Logger log = LoggerFactory.getLogger(FakeNaruMain.class);

    private FakeNaruMain() {
    }

    public static void main(String[] args) throws Exception {
        String profile = env("NARUPROF_PROFILE_ID", FixtureTraces.CONTRACT_PROFILE);
        String token = System.getenv("TWIN_TO_NARU_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TWIN_TO_NARU_TOKEN must be set");
        }
        int port = Integer.parseInt(env("FAKE_NARU_GRPC_PORT", "9092"));
        int controlPort = Integer.parseInt(env("FAKE_NARU_CONTROL_PORT", "9093"));
        FakeLearningProjection naru = new FakeLearningProjection();
        if ("empty".equals(env("FAKE_NARU_TRACE", "common"))) {
            naru.createProfile(profile);
        } else {
            FixtureTraces.commonTrace(naru, profile);
        }
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                .addService(ServerInterceptors.intercept(naru, new BearerAuthInterceptor(token)))
                .build().start();
        HttpServer control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), controlPort), 0);
        control.createContext("/", ex -> handle(ex, naru, profile));
        control.start();
        log.info("fake NARU publisher on 127.0.0.1:{} (control 127.0.0.1:{}), profile {}, head {}",
                port, controlPort, profile, naru.head(profile));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            control.stop(0);
            server.shutdown();
        }));
        server.awaitTermination();
    }

    private static void handle(HttpExchange ex, FakeLearningProjection naru, String profile) throws IOException {
        URI u = ex.getRequestURI();
        Map<String, String> q = query(u.getRawQuery());
        String body;
        int status = 200;
        try {
            String path = u.getPath();
            if ("GET".equals(ex.getRequestMethod()) && "/head".equals(path)) {
                body = "{\"profileId\":\"" + profile + "\",\"sequence\":\"" + naru.head(profile) + "\"}";
            } else if ("POST".equals(ex.getRequestMethod()) && "/commit".equals(path)) {
                long s = naru.commit(profile, l(q, "teaching"), l(q, "practice"), l(q, "propositions"), l(q, "chains"));
                body = "{\"sequence\":\"" + s + "\"}";
            } else if ("POST".equals(ex.getRequestMethod()) && "/delete".equals(path)) {
                body = "{\"sequence\":\"" + naru.commitDeletion(profile) + "\"}";
            } else if ("POST".equals(ex.getRequestMethod()) && "/rewind".equals(path)) {
                naru.rewindTo(profile, l(q, "to"));
                body = "{\"sequence\":\"" + naru.head(profile) + "\"}";
            } else if ("POST".equals(ex.getRequestMethod()) && "/available".equals(path)) {
                naru.setAvailable(Boolean.parseBoolean(q.getOrDefault("value", "true")));
                body = "{}";
            } else {
                status = 404;
                body = "{\"code\":\"not_found\"}";
            }
        } catch (RuntimeException e) {
            status = 409;
            body = "{\"code\":\"rejected\"}";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static long l(Map<String, String> q, String k) {
        return Long.parseLong(q.getOrDefault(k, "0"));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    m.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
        }
        return m;
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
