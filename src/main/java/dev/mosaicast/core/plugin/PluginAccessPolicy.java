// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Role;
import java.util.Optional;

/**
 * Decides who may read from and write to a plugin's generic doc-store surface (ARCHITECTURE §7.2/§7.6),
 * from the floors the manifest <strong>declares</strong> in its {@code data} block.
 *
 * <p>It used to derive them from slot {@code visibleTo}, taking the <em>minimum</em> across all slots as the
 * read floor. That coupled two unrelated decisions: a plugin with one anonymous display slot and one
 * admin-only slot served its whole doc store anonymously, including whatever the admin surface had written.
 * Which UI regions a plugin mounts into says nothing about who should read its data, and a plugin could not
 * separate the two without giving up one of them.
 *
 * <p>Absent declaration means the <em>closed</em> answer — reads default to the write floor, and the write
 * floor to {@code podcaster} — so a manifest that says nothing is not thereby public. Slot {@code visibleTo}
 * now governs rendering only.
 *
 * <p><strong>Still true, and worth stating:</strong> this decides access per plugin, not per document.
 * Clearing the floor grants every key in every <em>shared</em> scope. What it no longer grants is another
 * user's data — that lives in the {@code USER} scope, which no floor opens and no request can name
 * ({@link PluginDataController}).
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
        return rank(manifest.dataOrDefault().readableByOrDefault());
    }

    private static int writeFloor(PluginManifest manifest) {
        return rank(manifest.dataOrDefault().writableByOrDefault());
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
