// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PersonalAccessToken}. */
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    /** How many tokens a user currently holds — the per-user cap. */
    long countByUserId(UUID userId);

    List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PersonalAccessToken> findByIdAndUserId(UUID id, UUID userId);
}
