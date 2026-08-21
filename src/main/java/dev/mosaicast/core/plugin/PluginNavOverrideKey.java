// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link PluginNavOverride}: the owning plugin and the declared nav path.
 *
 * <p>The pair is the destination itself, which is what makes it the right key — an override follows the
 * entry it belongs to rather than a position in a list that shifts when a neighbour is removed. The path is
 * the empty string for a plugin's own root, which is a real destination and not a missing value.
 */
@Embeddable
public class PluginNavOverrideKey implements Serializable {

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "path", nullable = false)
    private String path;

    protected PluginNavOverrideKey() {
        // for JPA
    }

    public PluginNavOverrideKey(String pluginId, String path) {
        this.pluginId = pluginId;
        this.path = path == null ? "" : path;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getPath() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginNavOverrideKey other)) {
            return false;
        }
        return Objects.equals(pluginId, other.pluginId) && Objects.equals(path, other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, path);
    }
}
