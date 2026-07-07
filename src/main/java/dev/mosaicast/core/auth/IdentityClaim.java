// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

/**
 * The normalized identity a provider asserts on login (ARCHITECTURE §8.3), independent of which OAuth2
 * provider produced it. The account-merging rules operate purely on this.
 *
 * @param provider      the provider key (e.g. {@code discord}); never {@code null}
 * @param externalId    the provider's stable user id; never {@code null}
 * @param email         the asserted email, or {@code null} if the provider gave none
 * @param emailVerified whether the provider says the email is verified
 * @param displayName   a display name for a newly-created user; never {@code null}
 * @param avatarUrl     an avatar URL, or {@code null}
 */
public record IdentityClaim(
        String provider,
        String externalId,
        String email,
        boolean emailVerified,
        String displayName,
        String avatarUrl) {
}
