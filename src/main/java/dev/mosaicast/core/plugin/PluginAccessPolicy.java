// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Role;
import java.util.Optional;

/**
 * Decides who may read from and write to a plugin's generic doc-store surface (ARCHITECTURE §7.5/§7.6),
 * derived from the plugin's declared slot {@code visibleTo} floors:
 *
 * <ul>
 *   <li><strong>Read floor</strong> = the least-privileged {@code visibleTo} across all slots. The data API
 *       mirrors the plugin's most-public surface — if any slot is anonymous, reads are anonymous.</li>
 *   <li><strong>Write floor</strong> = the least-privileged <em>non-anonymous</em> {@code visibleTo} across
 *       slots (writes always need a signed-in user), defaulting to {@code PODCASTER} when a plugin declares
 *       no authenticated slot. So a fan-facing plugin admits fan writes; the sample's podcaster admin slot
 *       admits podcaster (and admin) writes.</li>
 * </ul>
 *
 * <p>This is the v1 rule for a generic, non-slot-specific data surface; a finer per-key policy can arrive
 * with a later contract version.
 */
final class PluginAccessPolicy {

    /** Privilege ranks: higher is more privileged. Anonymous is 0 (no role). */
    private static final int ANONYMOUS = 0;
    private static final int FAN = 1;
    private static final int PODCASTER = 2;
    private static final int ADMIN = 3;

    private PluginAccessPolicy() {
    }

    /** True if the (possibly absent) role may read this plugin's data. */
    static boolean canRead(PluginManifest manifest, Optional<Role> role) {
        return rank(role) >= readFloor(manifest);
    }

    /** True if the (possibly absent) role may write this plugin's data. */
    static boolean canWrite(PluginManifest manifest, Optional<Role> role) {
        return rank(role) >= writeFloor(manifest);
    }

    private static int readFloor(PluginManifest manifest) {
        return manifest.slots() == null ? PODCASTER
                : manifest.slots().stream().mapToInt(s -> rank(s.visibleTo())).min().orElse(PODCASTER);
    }

    private static int writeFloor(PluginManifest manifest) {
        if (manifest.slots() == null) {
            return PODCASTER;
        }
        return manifest.slots().stream()
                .mapToInt(s -> rank(s.visibleTo()))
                .filter(r -> r > ANONYMOUS)
                .min()
                .orElse(PODCASTER);
    }

    private static int rank(Optional<Role> role) {
        return role.map(PluginAccessPolicy::rank).orElse(ANONYMOUS);
    }

    private static int rank(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN;
            case PODCASTER -> PODCASTER;
            case FAN -> FAN;
        };
    }

    private static int rank(String visibleTo) {
        if (visibleTo == null) {
            return ANONYMOUS;
        }
        return switch (visibleTo.toLowerCase()) {
            case "admin" -> ADMIN;
            case "podcaster" -> PODCASTER;
            case "fan" -> FAN;
            default -> ANONYMOUS;
        };
    }
}
