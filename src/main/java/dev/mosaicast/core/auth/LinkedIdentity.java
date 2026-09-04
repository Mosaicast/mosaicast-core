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

    /**
     * The provider's own reference to this identity's picture — a Discord avatar hash, not a URL (§8.7).
     * Refreshed on every login with this provider, so a changed picture propagates. Null when the provider
     * has none, which is a normal state and not an error.
     */
    @Column(name = "avatar_ref")
    private String avatarRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LinkedIdentity() {
        // for JPA
    }

    private LinkedIdentity(UUID id, UUID userId, String provider, String externalId,
                           String email, boolean emailVerified, String avatarRef) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.externalId = externalId;
        this.email = email;
        this.emailVerified = emailVerified;
        this.avatarRef = avatarRef;
    }

    /** Links a provider identity to a user. */
    public static LinkedIdentity link(UUID userId, String provider, String externalId,
                                      String email, boolean emailVerified, String avatarRef) {
        return new LinkedIdentity(
                UUID.randomUUID(), userId, provider, externalId, email, emailVerified, avatarRef);
    }

    /**
     * Refreshes what the provider asserts on a later login: email, verification, and the avatar reference.
     *
     * <p>The picture is refreshed here rather than fetched on demand because this is the only moment the
     * host legitimately hears from the provider about this user. It is also why a changed Discord avatar
     * shows up after the next login rather than instantly — the alternative is polling a third party about
     * people who are not currently using the site.
     */
    public void refresh(String email, boolean emailVerified, String avatarRef) {
        this.email = email;
        this.emailVerified = emailVerified;
        this.avatarRef = avatarRef;
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

    public String getAvatarRef() {
        return avatarRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
