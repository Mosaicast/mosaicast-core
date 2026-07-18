// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * Thrown when a plugin's manifest is unusable or incompatible with the host contract (ARCHITECTURE §7.2).
 * The loader catches it and records the plugin as rejected with this message as the reason — a bad plugin
 * never aborts host startup (§7.8).
 */
public class PluginValidationException extends RuntimeException {

    /**
     * @param message the human-readable reason the plugin was rejected
     */
    public PluginValidationException(String message) {
        super(message);
    }
}
