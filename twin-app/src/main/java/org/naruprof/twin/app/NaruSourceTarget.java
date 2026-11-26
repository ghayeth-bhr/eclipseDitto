package org.naruprof.twin.app;

import java.util.Locale;

/**
 * Resolves the NARU gRPC target from explicit configuration. There is no default target: a blank
 * {@code NARU_GRPC_TARGET} fails startup instead of silently connecting to a bundled fake NARU.
 *
 * <ul>
 *   <li>{@code integrated} (default): the real NARU on another tailnet host. Loopback and the
 *       bundled {@code fake-naru} service are refused, so a leftover demo setting cannot
 *       masquerade as the real source.</li>
 *   <li>{@code fake}: the deterministic fixture publisher; must be selected explicitly
 *       ({@code TWIN_SOURCE_MODE=fake}).</li>
 * </ul>
 */
public final class NaruSourceTarget {

    public enum Mode { INTEGRATED, FAKE }

    private NaruSourceTarget() {
    }

    public static Mode mode(String text) {
        if (text == null || text.isBlank()) {
            return Mode.INTEGRATED;
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "integrated" -> Mode.INTEGRATED;
            case "fake" -> Mode.FAKE;
            default -> throw new IllegalStateException("TWIN_SOURCE_MODE must be 'integrated' or 'fake'");
        };
    }

    /** @return the validated {@code host:port} target */
    public static String resolve(Mode mode, String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalStateException(mode == Mode.INTEGRATED
                    ? "NARU_GRPC_TARGET must be set explicitly to <NARU_TS_IP>:9092 in integrated mode"
                    : "NARU_GRPC_TARGET must be set explicitly in fake mode (e.g. fake-naru:9092)");
        }
        String t = target.trim();
        int colon = t.lastIndexOf(':');
        if (colon <= 0 || colon == t.length() - 1) {
            throw new IllegalStateException("NARU_GRPC_TARGET must be host:port");
        }
        String host = t.substring(0, colon).toLowerCase(Locale.ROOT);
        try {
            int port = Integer.parseInt(t.substring(colon + 1));
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("NARU_GRPC_TARGET port is invalid");
        }
        if (mode == Mode.INTEGRATED && (host.equals("fake-naru") || host.equals("localhost")
                || host.startsWith("127.") || host.equals("[::1]") || host.equals("0.0.0.0"))) {
            throw new IllegalStateException("NARU_GRPC_TARGET points at a local/fake publisher; integrated mode "
                    + "requires the real NARU address (<NARU_TS_IP>:9092). Use TWIN_SOURCE_MODE=fake for demos.");
        }
        return t;
    }
}
