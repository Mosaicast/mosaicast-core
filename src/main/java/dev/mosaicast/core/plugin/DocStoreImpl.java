// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import java.util.Optional;

/**
 * A {@link DocStore} bound to one plugin (ARCHITECTURE §7.4/§7.6). The {@code pluginId} is fixed at
 * construction, so every call is hard-scoped to the owning plugin — a plugin can never reach another
 * plugin's data. All work delegates to the transactional {@link PluginDataService}.
 *
 * <p><strong>This is the backend's store, and a backend thread has no calling user.</strong> A
 * {@link ScopeType#USER} scope resolves to "the caller", and there is no caller here — a scheduled task or
 * {@code register(ctx)} runs on behalf of nobody. So every method refuses it rather than picking someone,
 * and {@link #queryAcrossUsers(String)} is the way to look at per-user data from the backend, being explicit
 * that it has no single owner.
 */
public class DocStoreImpl implements DocStore {

    private final String pluginId;
    private final PluginDataService service;

    public DocStoreImpl(String pluginId, PluginDataService service) {
        this.pluginId = pluginId;
        this.service = service;
    }

    @Override
    public <T> Optional<T> get(Scope scope, String key, Class<T> type) {
        refuseUserScope(scope);
        return service.get(pluginId, scope, key, type);
    }

    @Override
    public void put(Scope scope, String key, Object value) {
        refuseUserScope(scope);
        service.put(pluginId, scope, key, value);
    }

    @Override
    public boolean delete(Scope scope, String key) {
        refuseUserScope(scope);
        return service.delete(pluginId, scope, key);
    }

    @Override
    public List<DocEntry> query(Scope scope, String keyPrefix) {
        refuseUserScope(scope);
        return service.query(pluginId, scope, keyPrefix);
    }

    @Override
    public List<OwnedDocEntry> queryAcrossUsers(String keyPrefix) {
        return service.queryAcrossUsers(pluginId, keyPrefix);
    }

    /**
     * Refuses a {@code USER} scope, reads included.
     *
     * <p>Reads too, because resolving "me" without a caller would have to pick a user, and any pick is wrong
     * — silently returning one person's data to backend code that believed it was reading "the" partition is
     * worse than not answering. {@link UnsupportedOperationException} rather than
     * {@link IllegalArgumentException}: the argument is well-formed, the operation simply has no meaning in
     * this context, and the distinction is what a stack trace should say.
     */
    private static void refuseUserScope(Scope scope) {
        if (scope != null && scope.type() == ScopeType.USER) {
            throw new UnsupportedOperationException(
                    "USER scope has no meaning on a backend: there is no calling user. Use "
                            + "store().queryAcrossUsers(...) to aggregate, or address an entity scope.");
        }
    }
}
