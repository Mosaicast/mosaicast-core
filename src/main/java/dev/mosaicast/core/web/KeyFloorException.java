// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import org.springframework.security.access.AccessDeniedException;

/**
 * A client read or wrote a doc-store key whose {@code data.keyFloors} entry sets a floor above its role
 * (ARCHITECTURE §7.2/§7.6, core#259) — 403, with a problem {@code type} of its own beside the plugin-floor
 * ({@code forbidden}) and {@code backend-owned-key} ones, which the SDK exports as {@code PROBLEM_TYPES.keyFloor}.
 *
 * <p>Raised only <em>after</em> the plugin floor passed, so a caller who has no business in this plugin's data
 * never learns that a key has a floor of its own. The detail names the key the caller asked for and nothing
 * of the declaration: the selector and its role are the plugin's, and a reader below them is exactly who
 * they are kept from.
 *
 * <p>Extends {@link AccessDeniedException} for the reason {@link BackendOwnedKeyException} does: without its
 * handler it still degrades to a plain 403, never a 500.
 */
public class KeyFloorException extends AccessDeniedException {

    /**
     * @param pluginId the plugin whose manifest raised the key's floor
     * @param key      the key the client asked for
     * @param write    whether the refused request was a write or delete rather than a read
     */
    public KeyFloorException(String pluginId, String key, boolean write) {
        super("Key '%s' of plugin '%s' has a %s floor above your role (data.keyFloors)."
                .formatted(key, pluginId, write ? "write" : "read"));
    }
}
