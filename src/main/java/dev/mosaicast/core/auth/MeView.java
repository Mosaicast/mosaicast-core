// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.util.UUID;

/**
 * The current user as exposed to the shell (ARCHITECTURE §8.5). The role is lower-cased to match the
 * plugin {@code ctx.user.role} contract ({@code admin}/{@code podcaster}/{@code fan}).
 */
public record MeView(UUID id, String displayName, String avatarUrl, String role) {

    public static MeView of(User user) {
        return new MeView(
                user.getId(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getRole().name().toLowerCase());
    }
}
