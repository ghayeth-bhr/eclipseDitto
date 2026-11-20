package org.naruprof.twin.infra;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque pseudonymous learner profile id. Always a parsed UUID rendered as canonical lowercase
 * text, so "F7933216-..." and "f7933216-..." name the same profile and no other text is accepted.
 */
public final class ProfileId {

    private final UUID uuid;

    private ProfileId(UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid);
    }

    public static ProfileId parse(String text) {
        if (text == null || text.length() != 36) {
            throw new IllegalArgumentException("profile id must be a canonical UUID");
        }
        UUID u;
        try {
            u = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("profile id must be a canonical UUID");
        }
        // UUID.fromString is lenient (e.g. short groups); require the round trip.
        if (!u.toString().equals(text.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("profile id must be a canonical UUID");
        }
        return new ProfileId(u);
    }

    public static ProfileId of(UUID uuid) {
        return new ProfileId(uuid);
    }

    public UUID uuid() {
        return uuid;
    }

    /** Canonical lowercase text. */
    public String value() {
        return uuid.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProfileId p && p.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return value();
    }
}
