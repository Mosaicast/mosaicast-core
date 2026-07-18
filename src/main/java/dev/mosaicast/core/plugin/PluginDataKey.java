// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of a {@link PluginData} row (ARCHITECTURE §7.6): the owning plugin plus the
 * {@code (scopeType, scopeId, key)} address of the document. {@code pluginId} is the hard scope — the host
 * binds it, so a plugin can never address another plugin's data.
 */
@Embeddable
public class PluginDataKey implements Serializable {

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private String scopeId;

    @Column(name = "key", nullable = false)
    private String key;

    protected PluginDataKey() {
        // for JPA
    }

    public PluginDataKey(String pluginId, String scopeType, String scopeId, String key) {
        this.pluginId = pluginId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.key = key;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginDataKey that)) {
            return false;
        }
        return Objects.equals(pluginId, that.pluginId)
                && Objects.equals(scopeType, that.scopeType)
                && Objects.equals(scopeId, that.scopeId)
                && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, scopeType, scopeId, key);
    }
}
