// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.CrossUserStore;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import java.util.List;

/**
 * The {@link CrossUserStore} bound to one plugin — {@code PluginContext.allUsers()} (SDK 0.16.0).
 *
 * <p>Built only for a plugin whose manifest declares {@code data.readsAllUsers}; every other plugin gets
 * {@code null} (see {@link PluginLoaderService}). The read itself is unchanged from when it was
 * {@code DocStore.queryAcrossUsers}: hard-scoped to the plugin id, every {@code USER} partition, with the
 * owner resolved from the partition rather than supplied by anyone. What changed is who gets to call it.
 */
public class CrossUserStoreImpl implements CrossUserStore {

    private final String pluginId;
    private final PluginDataService service;

    public CrossUserStoreImpl(String pluginId, PluginDataService service) {
        this.pluginId = pluginId;
        this.service = service;
    }

    @Override
    public List<OwnedDocEntry> query(String keyPrefix) {
        return service.queryAcrossUsers(pluginId, keyPrefix);
    }
}
