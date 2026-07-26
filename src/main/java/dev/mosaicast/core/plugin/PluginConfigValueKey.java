// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link PluginConfigValue}: the owning plugin and the declared field name. */
@Embeddable
public class PluginConfigValueKey implements Serializable {

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "key", nullable = false)
    private String key;

    protected PluginConfigValueKey() {
        // for JPA
    }

    public PluginConfigValueKey(String pluginId, String key) {
        this.pluginId = pluginId;
        this.key = key;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginConfigValueKey other)) {
            return false;
        }
        return Objects.equals(pluginId, other.pluginId) && Objects.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, key);
    }
}
