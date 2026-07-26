// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PluginConfigValue}; every lookup is scoped to the owning plugin. */
public interface PluginConfigValueRepository extends JpaRepository<PluginConfigValue, PluginConfigValueKey> {

    /** Every admin-set override of one plugin. */
    List<PluginConfigValue> findByIdPluginId(String pluginId);
}
