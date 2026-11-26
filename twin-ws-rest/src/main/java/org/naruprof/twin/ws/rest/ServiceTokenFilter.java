package org.naruprof.twin.ws.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Per-role bearer credentials for the private surface:
 * <ul>
 *   <li>{@code /internal/v1/profiles/**}: the Vert.x service credential (VERTX_TO_TWIN_TOKEN);</li>
 *   <li>{@code /internal/v1/projection/**}, {@code /internal/v1/maintenance/**}: the operator
 *       maintenance credential (disabled when not configured);</li>
 *   <li>exactly {@code GET /internal/health/live} and {@code GET /internal/health/ready}: open for
 *       launcher probes (private listener only). There is no blanket {@code /internal/**} exemption:
 *       any other method or path below {@code /internal/health/} is not exempt.</li>
 * </ul>
 * Anything else is 404. Constant-time comparison; tokens are never logged.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    static final String HEALTH_LIVE = "/internal/health/live";
    static final String HEALTH_READY = "/internal/health/ready";

    private final byte[] vertxToken;
    private final byte[] maintenanceToken;

    public ServiceTokenFilter(char[] vertxToken, char[] maintenanceToken) {
        if (vertxToken == null || vertxToken.length < 16) {
            throw new IllegalArgumentException("VERTX_TO_TWIN_TOKEN must be configured (>= 16 chars)");
        }
        this.vertxToken = new String(vertxToken).getBytes(StandardCharsets.UTF_8);
        this.maintenanceToken = maintenanceToken == null || maintenanceToken.length == 0
                ? null : new String(maintenanceToken).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // container-normalized, decoded path (no "..", ";params" or context path tricks)
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        if (HEALTH_LIVE.equals(path) || HEALTH_READY.equals(path)) {
            if ("GET".equals(req.getMethod())) {
                chain.doFilter(req, res);
            } else {
                res.setHeader("Allow", "GET");
                write(res, 405, "{\"code\":\"method_not_allowed\"}");
            }
            return;
        }
        byte[] expected;
        if (path.startsWith("/internal/v1/profiles/")) {
            expected = vertxToken;
        } else if (path.startsWith("/internal/v1/projection/") || path.startsWith("/internal/v1/maintenance/")) {
            expected = maintenanceToken;
            if (expected == null) {
                write(res, 403, "{\"code\":\"maintenance_disabled\"}");
                return;
            }
        } else {
            write(res, 404, "{\"code\":\"not_found\"}");
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            res.setHeader("WWW-Authenticate", "Bearer");
            write(res, 401, "{\"code\":\"unauthorized\"}");
            return;
        }
        byte[] presented = auth.substring(7).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            // a valid credential of ANOTHER role is authenticated but not allowed here
            boolean otherRole = (expected != vertxToken && MessageDigest.isEqual(vertxToken, presented))
                    || (maintenanceToken != null && expected != maintenanceToken && MessageDigest.isEqual(maintenanceToken, presented));
            if (otherRole) {
                write(res, 403, "{\"code\":\"forbidden\"}");
            } else {
                res.setHeader("WWW-Authenticate", "Bearer");
                write(res, 401, "{\"code\":\"unauthorized\"}");
            }
            return;
        }
        chain.doFilter(req, res);
    }

    private static void write(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setHeader("Cache-Control", "no-store");
        res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
