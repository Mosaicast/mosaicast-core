// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Optional;

/**
 * A {@link DocStore} bound to one plugin (ARCHITECTURE §7.4/§7.6). The {@code pluginId} is fixed at
 * construction, so every call is hard-scoped to the owning plugin — a plugin can never reach another
 * plugin's data. All work delegates to the transactional {@link PluginDataService}.
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
        return service.get(pluginId, scope, key, type);
    }

    @Override
    public void put(Scope scope, String key, Object value) {
        service.put(pluginId, scope, key, value);
    }

    @Override
    public boolean delete(Scope scope, String key) {
        return service.delete(pluginId, scope, key);
    }

    @Override
    public List<DocEntry> query(Scope scope, String keyPrefix) {
        return service.query(pluginId, scope, keyPrefix);
    }
}
