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

    /**
     * The canonical form of {@link #displayName}, unique across the site (§8.6). Never shown: it exists so
     * that {@code Maritime} and a Cyrillic-a {@code Mаritime} cannot both exist, without lower-casing what
     * a visitor reads.
     */
    @Column(name = "display_key", nullable = false, unique = true)
    private String displayKey;

    /**
     * Which linked identity supplies this user's picture, or null for the generated avatar (§8.7). A
     * provider name, not a URL: the URL is composed in code, so nothing attacker-influenced reaches the
     * fetch, and the Discord snowflake it contains never leaves the server.
     */
    @Column(name = "avatar_provider")
    private String avatarProvider;

    /**
     * When this user may next change their own name (§8.6) — the rename cooldown, and the freeze an admin
     * revert applies (§8.6.1) so the name does not simply go straight back. Null means no restriction.
     */
    @Column(name = "rename_locked_until")
    private Instant renameLockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() {
        // for JPA
    }

    private User(UUID id, String displayName, String displayKey, String avatarProvider, Role role) {
        this.id = id;
        this.displayName = displayName;
        this.displayKey = displayKey;
        this.avatarProvider = avatarProvider;
        this.role = role;
    }

    /**
     * Creates a new user with the given profile and role.
     *
     * <p>The caller supplies the id rather than this factory minting one, because the name may have to be
     * <em>derived</em> from it: a sign-up whose provider name is unusable or already taken falls back to
     * {@link DisplayNames#generatedFor}, which needs a name that is unique without a lookup, and the id is
     * the only thing to hand that already is (§8.6).
     */
    public static User create(UUID id, String displayName, String displayKey, String avatarProvider,
                              Role role) {
        return new User(id, displayName, displayKey, avatarProvider, role);
    }

    /** Changes the user's role (admin promotes fans → podcasters, §8.5). */
    public void changeRole(Role role) {
        this.role = role;
    }

    /**
     * Sets the display name and its key together (§8.6).
     *
     * <p>They are one operation and not two setters on purpose — a row whose {@code display_key} does not
     * match its {@code display_name} is a name that passes uniqueness under the wrong identity, and the
     * only way to guarantee that never happens is to make it unexpressible.
     *
     * @param displayName the visible form, already cleaned
     * @param displayKey  its canonical form, from {@link DisplayNames#canonicalise}
     */
    public void rename(String displayName, String displayKey) {
        this.displayName = displayName;
        this.displayKey = displayKey;
    }

    /** Blocks further self-renames until {@code until}; null lifts the lock. */
    public void lockRenameUntil(Instant until) {
        this.renameLockedUntil = until;
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public Instant getRenameLockedUntil() {
        return renameLockedUntil;
    }

    public String getAvatarProvider() {
        return avatarProvider;
    }

    /**
     * Chooses which linked identity supplies the picture; null selects the generated avatar (§8.7).
     *
     * <p>Callers must clear this when the named identity goes away — unlinking or erasure — or the user is
     * left pointing at a source that can never answer.
     */
    public void useAvatarFrom(String provider) {
        this.avatarProvider = provider;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
