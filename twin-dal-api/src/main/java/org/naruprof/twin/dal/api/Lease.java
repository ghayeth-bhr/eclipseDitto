package org.naruprof.twin.dal.api;

import org.naruprof.twin.infra.ProfileId;

import java.time.Instant;

/** Fenced single-writer ownership of one profile projection. Token increases on every takeover. */
public record Lease(ProfileId profile, String owner, long fencingToken, Instant expiresAt) {
}
