// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link User}. */
public interface UserRepository extends JpaRepository<User, UUID> {
}
