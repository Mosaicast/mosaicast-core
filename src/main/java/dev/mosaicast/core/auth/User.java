// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.plugin.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person with access to the site (ARCHITECTURE §8.2). Identity providers are attached as
 * {@link LinkedIdentity} rows; this row holds only the profile and role. No password is ever stored
 * (§8.1). The {@link Role} enum is the same one plugins see via {@code ctx.user.role}.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() {
        // for JPA
    }

    private User(UUID id, String displayName, String avatarUrl, Role role) {
        this.id = id;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.role = role;
    }

    /** Creates a new user with the given profile and role. */
    public static User create(String displayName, String avatarUrl, Role role) {
        return new User(UUID.randomUUID(), displayName, avatarUrl, role);
    }

    /** Changes the user's role (admin promotes fans → podcasters, §8.5). */
    public void changeRole(Role role) {
        this.role = role;
    }

    public void updateProfile(String displayName, String avatarUrl) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
