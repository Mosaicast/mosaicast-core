// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import org.springframework.security.access.AccessDeniedException;

/**
 * A client tried to read, write or delete a doc-store key the plugin's manifest keeps for its backend alone
 * ({@code data.backendPrivate}, platformApi 0.20.0, ARCHITECTURE §7.6) — 403 with a type of its own, whoever
 * asks, admins included.
 *
 * <p>A refusal rather than a pretended absence: the key names are in the manifest, so answering openly reveals
 * nothing a 204 would hide, and an author whose UI reads one of its own private keys gets told why instead of
 * seeing "not set" forever. Extends {@link AccessDeniedException} for the reason {@link BackendOwnedKeyException}
 * does: without its handler it degrades to a plain 403.
 */
public class BackendPrivateKeyException extends AccessDeniedException {

    /**
     * @param pluginId the plugin whose manifest keeps the key
     * @param key      the key the client addressed
     * @param pattern  the {@code data.backendPrivate} entry that matched, named so the author can find it
     */
    public BackendPrivateKeyException(String pluginId, String key, String pattern) {
        super(("Key '%s' is backend-private to plugin '%s' (data.backendPrivate: '%s'); no client may read "
                + "or write it.").formatted(key, pluginId, pattern));
    }
}
