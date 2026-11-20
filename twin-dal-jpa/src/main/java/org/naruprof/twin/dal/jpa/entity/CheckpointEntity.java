package org.naruprof.twin.dal.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** One row per profile: the unique checkpoint key is the profile UUID. uint64 values as decimal text. */
@Entity
@Table(name = "twin_checkpoint")
public class CheckpointEntity {

    @Id
    @Column(name = "profile_id", length = 36, nullable = false, updatable = false)
    private String profileId;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "applied_sequence", length = 20)
    private String appliedSequence;

    @Column(name = "tombstone_sequence", length = 20)
    private String tombstoneSequence;

    @Column(name = "conflict_sequence", length = 20)
    private String conflictSequence;

    @Column(name = "last_writer_token", nullable = false)
    private long lastWriterToken;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected CheckpointEntity() {
    }

    public CheckpointEntity(String profileId, String status, Instant now) {
        this.profileId = profileId;
        this.status = status;
        this.updatedAt = now;
    }

    public String getProfileId() { return profileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAppliedSequence() { return appliedSequence; }
    public void setAppliedSequence(String v) { this.appliedSequence = v; }
    public String getTombstoneSequence() { return tombstoneSequence; }
    public void setTombstoneSequence(String v) { this.tombstoneSequence = v; }
    public String getConflictSequence() { return conflictSequence; }
    public void setConflictSequence(String v) { this.conflictSequence = v; }
    public long getLastWriterToken() { return lastWriterToken; }
    public void setLastWriterToken(long t) { this.lastWriterToken = t; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
