// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.nio.file.Path;

/**
 * The load outcome of one discovered plugin folder (ARCHITECTURE §7.8). A plugin is {@code LOADED}
 * (registered and serving), {@code DISABLED} (an admin switched it off, so its backend was never started),
 * or {@code REJECTED} with a human-readable reason; in every case the host keeps booting. Surfaced to admins
 * via {@code GET /api/admin/plugins}.
 *
 * @param id        the plugin id (or the folder name when the manifest could not be read)
 * @param status    load state
 * @param reason    why it was rejected, or {@code null} when loaded or disabled
 * @param manifest  the parsed manifest, or {@code null} when it could not be read/validated
 * @param directory the plugin folder on disk (for asset serving)
 */
public record PluginRegistration(
        String id, Status status, String reason, PluginManifest manifest, Path directory) {

    /** Load state of a discovered plugin. */
    public enum Status {
        LOADED,
        DISABLED,
        REJECTED
    }

    public static PluginRegistration loaded(PluginManifest manifest, Path directory) {
        return new PluginRegistration(manifest.id(), Status.LOADED, null, manifest, directory);
    }

    /**
     * A valid plugin the host did not start because it is switched off. Its manifest is kept so the admin
     * surface can still show its name, version and config fields — you must be able to configure a plugin
     * before switching it back on.
     */
    public static PluginRegistration disabled(PluginManifest manifest, Path directory) {
        return new PluginRegistration(manifest.id(), Status.DISABLED, null, manifest, directory);
    }

    public static PluginRegistration rejected(String id, String reason, PluginManifest manifest, Path directory) {
        return new PluginRegistration(id, Status.REJECTED, reason, manifest, directory);
    }

    public boolean isLoaded() {
        return status == Status.LOADED;
    }
}
