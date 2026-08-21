// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An admin's decision about one navigation entry (ARCHITECTURE §7.3): whether it appears, and where.
 *
 * <p>Only explicit decisions are stored, the same way {@link PluginActivation} stores only explicit
 * switch-offs. An entry with no row shows, at the order its manifest asked for — so installing a plugin
 * needs no bookkeeping and an untouched install has an empty table.
 *
 * <p>A row whose entry the plugin no longer declares is <strong>inert, not stale data to clean up</strong>:
 * resolution starts from the manifest, so an override with nothing to override never surfaces. That is the
 * same property {@code PluginConfigImpl} relies on for config values, and it is what makes it safe for a
 * plugin to rename or drop an entrance between versions.
 */
@Entity
@Table(name = "plugin_nav_override")
public class PluginNavOverride {

    @EmbeddedId
    private PluginNavOverrideKey id;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PluginNavOverride() {
        // for JPA
    }

    public PluginNavOverride(String pluginId, String path, boolean enabled, int sortOrder) {
        this.id = new PluginNavOverrideKey(pluginId, path);
        this.enabled = enabled;
        this.sortOrder = sortOrder;
    }

    /** Records a new admin decision. */
    public void update(boolean enabled, int sortOrder) {
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.updatedAt = Instant.now();
    }

    public PluginNavOverrideKey getId() {
        return id;
    }

    public String getPluginId() {
        return id.getPluginId();
    }

    public String getPath() {
        return id.getPath();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
