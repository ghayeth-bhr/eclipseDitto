package org.naruprof.twin.dal.ditto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * One authenticated Ditto subject. The private gateway front (nginx) maps the basic-auth user
 * to the Ditto subject {@code nginx:<user>}. The password never appears in toString/logs.
 */
public final class DittoIdentity {

    private final String role;
    private final String user;
    private final String authorizationHeader;

    public DittoIdentity(String role, String user, char[] password) {
        this.role = Objects.requireNonNull(role);
        this.user = Objects.requireNonNull(user);
        Objects.requireNonNull(password);
        if (user.isBlank() || password.length == 0) {
            throw new IllegalArgumentException("Ditto credential for role " + role + " is not configured");
        }
        String raw = user + ":" + new String(password);
        this.authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String role() {
        return role;
    }

    public String user() {
        return user;
    }

    String authorizationHeader() {
        return authorizationHeader;
    }

    @Override
    public String toString() {
        return "DittoIdentity[" + role + "]";
    }
}
