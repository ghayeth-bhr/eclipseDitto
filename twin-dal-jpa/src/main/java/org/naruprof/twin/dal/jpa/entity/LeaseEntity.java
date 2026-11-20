package org.naruprof.twin.dal.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "twin_projection_lease")
public class LeaseEntity {

    @Id
    @Column(name = "profile_id", length = 36, nullable = false, updatable = false)
    private String profileId;

    @Column(name = "owner", length = 200, nullable = false)
    private String owner;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected LeaseEntity() {
    }

    public LeaseEntity(String profileId, String owner, long fencingToken, Instant expiresAt) {
        this.profileId = profileId;
        this.owner = owner;
        this.fencingToken = fencingToken;
        this.expiresAt = expiresAt;
    }

    public String getProfileId() { return profileId; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long t) { this.fencingToken = t; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant t) { this.expiresAt = t; }
}
