// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link LinkedIdentity}. */
public interface LinkedIdentityRepository extends JpaRepository<LinkedIdentity, UUID> {

    /** Looks up an identity by its stable key (§8.3 case 1). */
    Optional<LinkedIdentity> findByProviderAndExternalId(String provider, String externalId);

    /** All identities of a user (for the settings provider list and lockout check, §8.4). */
    List<LinkedIdentity> findByUserId(UUID userId);

    /** Every identity of a user — deleted with the account, which is what cuts the link to a person (§12). */
    void deleteByUserId(UUID userId);

    /** A user's identity for a given provider, if linked. */
    Optional<LinkedIdentity> findByUserIdAndProvider(UUID userId, String provider);

    /** How many identities a user has — the last one cannot be removed (§8.4). */
    long countByUserId(UUID userId);

    /**
     * Verified identities sharing an email with another provider — the conservative merge check only
     * auto-links when both sides are verified (§8.3).
     */
    List<LinkedIdentity> findByEmailAndEmailVerifiedTrue(String email);
}
