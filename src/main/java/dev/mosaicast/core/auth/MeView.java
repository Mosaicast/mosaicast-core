// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.util.UUID;

/**
 * The current user as exposed to the shell (ARCHITECTURE §8.5). The role is lower-cased to match the
 * plugin {@code ctx.user.role} contract ({@code admin}/{@code podcaster}/{@code fan}).
 */
public record MeView(
        UUID id, String displayName, String avatarUrl, String avatarProvider, String role) {

    /**
     * The host's own avatar endpoint (§8.7) — never a provider URL.
     *
     * <p>Always populated, because {@code /api/users/{id}/avatar} always answers: a user with no provider
     * picture gets the generated one, so no caller has to implement a fallback or tell "no picture" apart
     * from "no such user".
     */
    public static String avatarUrlFor(UUID userId) {
        return "/api/users/" + userId + "/avatar";
    }

    public static MeView of(User user) {
        return new MeView(
                user.getId(),
                user.getDisplayName(),
                avatarUrlFor(user.getId()),
                user.getAvatarProvider(),
                user.getRole().name().toLowerCase());
    }
}
