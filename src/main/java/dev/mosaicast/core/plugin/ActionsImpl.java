// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.ActionHandler;
import dev.mosaicast.plugin.api.Actions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One plugin's action handlers ({@code ctx.actions()}, platformApi 0.20.0, ARCHITECTURE §7.9).
 *
 * <p>Only a name the manifest declares can be registered: the manifest is the operator-readable list of what
 * a plugin exposes, and a handler registered under a name it does not list would be a function the operator
 * never saw. Registering a declared name twice replaces the handler — the last {@code register} wins, as it
 * does for a schedule's period.
 */
final class ActionsImpl implements Actions {

    private final PluginManifest manifest;
    private final ConcurrentHashMap<String, ActionHandler> handlers = new ConcurrentHashMap<>();

    ActionsImpl(PluginManifest manifest) {
        this.manifest = manifest;
    }

    @Override
    public void handle(String name, ActionHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (!manifest.actions().declares(name)) {
            throw new IllegalArgumentException(
                    "action '%s' is not declared in plugin '%s''s manifest (actions.names)"
                            .formatted(name, manifest.id()));
        }
        handlers.put(name, handler);
    }

    /** The handler for a declared name, or empty when the backend never registered one. */
    Optional<ActionHandler> handlerOf(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    /** Drops every handler — a plugin whose {@code register} failed keeps nothing it half-wired. */
    void clear() {
        handlers.clear();
    }
}
