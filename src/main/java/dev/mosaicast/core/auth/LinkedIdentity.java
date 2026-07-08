// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A social login linked to a {@link User} (ARCHITECTURE §8.2). The stable key is
 * {@code (provider, external_id)} — never the email, which can change and be reused. Keeping the
 * provider's {@code external_id} enables future integrations (e.g. Discord role sync).
 */
@Entity
@Table(name = "linked_identity")
public class LinkedIdentity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LinkedIdentity() {
        // for JPA
    }

    private LinkedIdentity(UUID id, UUID userId, String provider, String externalId,
                           String email, boolean emailVerified) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.externalId = externalId;
        this.email = email;
        this.emailVerified = emailVerified;
    }

    /** Links a provider identity to a user. */
    public static LinkedIdentity link(UUID userId, String provider, String externalId,
                                      String email, boolean emailVerified) {
        return new LinkedIdentity(UUID.randomUUID(), userId, provider, externalId, email, emailVerified);
    }

    /** Refreshes the email/verification captured from the provider on a later login. */
    public void refresh(String email, boolean emailVerified) {
        this.email = email;
        this.emailVerified = emailVerified;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
