// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The store an action hands its handler ({@code ActionCall.store()}, platformApi 0.20.0, ARCHITECTURE §7.9):
 * the plugin's own store, except that {@code USER} "me" reaches the caller's partition.
 *
 * <p>The one place a backend can touch a {@code USER} partition, because it is the one place there is a
 * caller to resolve it to. Any other {@code USER} id is refused exactly as over HTTP — another person's
 * partition is unnameable here too. What lands in the partition stays the user's: they can overwrite it over
 * HTTP, so nothing written here is authoritative (§7.6).
 */
final class CallerDocStore implements DocStore {

    private final String pluginId;
    private final UUID caller;
    private final DocStore shared;
    private final PluginDataService service;

    CallerDocStore(String pluginId, UUID caller, DocStore shared, PluginDataService service) {
        this.pluginId = pluginId;
        this.caller = caller;
        this.shared = shared;
        this.service = service;
    }

    @Override
    public <T> Optional<T> get(Scope scope, String key, Class<T> type) {
        return isUser(scope) ? service.get(pluginId, mine(scope), key, type) : shared.get(scope, key, type);
    }

    @Override
    public void put(Scope scope, String key, Object value) {
        if (isUser(scope)) {
            service.put(pluginId, mine(scope), key, value);
        } else {
            shared.put(scope, key, value);
        }
    }

    @Override
    public boolean delete(Scope scope, String key) {
        return isUser(scope) ? service.delete(pluginId, mine(scope), key) : shared.delete(scope, key);
    }

    @Override
    public List<DocEntry> query(Scope scope, String keyPrefix) {
        return isUser(scope) ? service.query(pluginId, mine(scope), keyPrefix) : shared.query(scope, keyPrefix);
    }

    private static boolean isUser(Scope scope) {
        return scope != null && scope.type() == ScopeType.USER;
    }

    private DataScope mine(Scope scope) {
        if (!Scope.SELF_ID.equals(scope.id())) {
            throw new IllegalArgumentException(
                    "USER scope is addressed as '" + Scope.SELF_ID + "'; it resolves to the action's caller.");
        }
        return DataScope.ofUser(caller);
    }
}
