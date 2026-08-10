// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import org.springframework.security.access.AccessDeniedException;

/**
 * A client tried to write a doc-store key the plugin's manifest reserves for its own backend
 * ({@code data.backendOwned}, ARCHITECTURE §7.2/§7.6) — 403, and deliberately a <em>different</em> 403 from
 * the role-floor one.
 *
 * <p>The plugin contract promises the two are distinguishable (SDK {@code DocStore.BACKEND_OWNED_PATTERN}):
 * an author whose write is refused has to be able to tell "your role is too low" from "that key is not
 * yours to write", because the fixes are opposite — raise the floor, or stop writing it from the client.
 * The {@code detail} says which in English; the problem {@code type} says it in a form that survives a
 * rewording, which is what a plugin's own test should key on.
 *
 * <p>It extends {@link AccessDeniedException} rather than standing beside it. Spring picks the most specific
 * handler, so the dedicated one still wins — but if that handler is ever removed, or this escapes to a path
 * the advice does not cover, it degrades to a plain 403 instead of a catch-all 500. For a refusal, failing in
 * the safe direction by construction is worth more than a tidier hierarchy.
 */
public class BackendOwnedKeyException extends AccessDeniedException {

    /**
     * @param pluginId the plugin whose manifest reserved the key
     * @param key      the key the client tried to write or delete
     * @param pattern  the {@code data.backendOwned} entry that matched, named so the author can find it
     */
    public BackendOwnedKeyException(String pluginId, String key, String pattern) {
        super(("Key '%s' is backend-owned by plugin '%s' (data.backendOwned: '%s'); a client may read it "
                + "but only the plugin's backend may write it.").formatted(key, pluginId, pattern));
    }
}
