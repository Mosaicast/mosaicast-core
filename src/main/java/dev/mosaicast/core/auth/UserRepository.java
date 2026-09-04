// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.plugin.api.Role;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link User}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** How many users hold a given role — used to protect the last ADMIN from being demoted (§8.5). */
    long countByRole(Role role);

    /**
     * Whether a canonical name is taken (§8.6). The unique index is what actually enforces this; the check
     * exists so the common case answers with a translated refusal instead of a constraint violation.
     */
    boolean existsByDisplayKey(String displayKey);

    /** As {@link #existsByDisplayKey}, ignoring one user — a rename must not collide with itself. */
    boolean existsByDisplayKeyAndIdNot(String displayKey, UUID id);

    /**
     * The admin user list, filtered by a canonicalised name fragment (§8.5).
     *
     * <p>Matching on {@code display_key} rather than {@code display_name} is what makes the search useful
     * to a moderator: someone reported for impersonation is found by typing the name they are imitating,
     * even though the account spells it with a Cyrillic character precisely so that it does not match.
     */
    org.springframework.data.domain.Page<User> findByDisplayKeyContaining(
            String fragment, org.springframework.data.domain.Pageable pageable);
}
