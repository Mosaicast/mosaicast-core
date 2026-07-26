// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An admin's explicit decision to switch a plugin off (ARCHITECTURE §7.8, §8.5). Only explicit decisions are
 * stored — a plugin with no row is enabled — so installing a plugin needs no bookkeeping.
 *
 * <p>Named for the decision, not for PF4J's {@code PluginState}: this is host-side activation, independent of
 * the PF4J lifecycle of the loaded extension.
 */
@Entity
@Table(name = "plugin_activation")
public class PluginActivation {

    @Id
    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PluginActivation() {
        // for JPA
    }

    public PluginActivation(String pluginId, boolean enabled) {
        this.pluginId = pluginId;
        this.enabled = enabled;
    }

    /** Records a new admin decision. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public String getPluginId() {
        return pluginId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
