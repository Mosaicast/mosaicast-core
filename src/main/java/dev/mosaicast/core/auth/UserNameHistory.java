// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Every name a user has held (ARCHITECTURE §8.6). It exists so an admin can <em>revert</em> a name without
 * ever typing one (§8.6.1) — the previous self-chosen entry is the target, and without a record there is
 * nothing to walk back to.
 *
 * <p><strong>This is personal data with a short life.</strong> §8.6 requires it retention-capped and erased
 * with the account, because the mechanism that lets someone shed a name must not quietly become a permanent
 * register of every name they tried to leave behind.
 */
@Entity
@Table(name = "user_name_history")
public class UserNameHistory {

    /** Who set the name — the distinction a revert needs in order to skip its own earlier work. */
    public enum SetBy {
        /** The user chose it. Only these are revert targets. */
        SELF,
        /** The host assigned it: the provider prefill at sign-up, or the generated floor name. */
        SYSTEM,
        /** An admin walked the name back (§8.6.1). Never a revert target, or a revert would oscillate. */
        ADMIN_REVERT
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "set_by", nullable = false, updatable = false)
    private SetBy setBy;

    @Column(name = "set_at", nullable = false, updatable = false)
    private Instant setAt = Instant.now();

    protected UserNameHistory() {
        // for JPA
    }

    private UserNameHistory(UUID userId, String name, SetBy setBy) {
        this.userId = userId;
        this.name = name;
        this.setBy = setBy;
    }

    /** Records that {@code userId} now holds {@code name}, set by {@code setBy}. */
    public static UserNameHistory of(UUID userId, String name, SetBy setBy) {
        return new UserNameHistory(userId, name, setBy);
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

    public SetBy getSetBy() {
        return setBy;
    }

    public Instant getSetAt() {
        return setAt;
    }
}
