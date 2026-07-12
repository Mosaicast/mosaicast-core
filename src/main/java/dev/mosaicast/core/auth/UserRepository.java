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
}
