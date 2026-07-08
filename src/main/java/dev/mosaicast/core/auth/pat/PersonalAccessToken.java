// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A personal access token for automation (ARCHITECTURE §8.5). Only the SHA-256 {@code tokenHash} is
 * stored — the secret is shown once at creation. The {@code prefix} is kept only so the owner can tell
 * tokens apart in the UI.
 */
@Entity
@Table(name = "personal_access_token")
public class PersonalAccessToken {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private String prefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected PersonalAccessToken() {
        // for JPA
    }

    PersonalAccessToken(UUID userId, String name, String tokenHash, String prefix) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.tokenHash = tokenHash;
        this.prefix = prefix;
    }

    void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
