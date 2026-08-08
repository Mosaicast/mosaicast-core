// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Host-owned settings of a plugin: whether it is activated and which of its declared config fields an admin
 * has overridden (ARCHITECTURE §7.2, §7.8). Deliberately knows nothing about manifests — declaration-level
 * rules (does the field exist, does the value match its declared type, may this role edit it) live in
 * {@link AdminPluginController}, which owns both sides. That keeps this service free of a dependency on
 * {@link PluginLoaderService}, which in turn depends on it.
 *
 * <p>Reads sit on the hot path — every doc-store write, every scheduler tick, every manifest fetch asks
 * whether a plugin is enabled — so both maps are cached in memory and invalidated on write. The cache is
 * per-instance; with more than one instance an admin's toggle reaches the others on their next restart,
 * which matches the "boot for the backend" activation semantics.
 */
@Service
public class PluginSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PluginSettingsService.class);

    private final PluginActivationRepository activations;
    private final PluginConfigValueRepository configValues;
    private final PluginDataRepository data;

    /** pluginId → explicit admin decision. Absent = never toggled = enabled. */
    private final Map<String, Boolean> enabledCache = new ConcurrentHashMap<>();

    /** pluginId → field → override. Absent field = the manifest default applies. */
    private final Map<String, Map<String, JsonNode>> configCache = new ConcurrentHashMap<>();

    public PluginSettingsService(PluginActivationRepository activations,
                                 PluginConfigValueRepository configValues,
                                 PluginDataRepository data) {
        this.activations = activations;
        this.configValues = configValues;
        this.data = data;
    }

    /**
     * Whether the plugin may currently serve. A plugin nobody ever toggled is enabled, so a fresh install
     * works without an admin action.
     */
    @Transactional(readOnly = true)
    public boolean enabled(String pluginId) {
        return enabledCache.computeIfAbsent(pluginId,
                id -> activations.findById(id).map(PluginActivation::isEnabled).orElse(true));
    }

    /** Records an admin's activation decision. */
    @Transactional
    public void setEnabled(String pluginId, boolean enabled) {
        activations.findById(pluginId).ifPresentOrElse(
                existing -> existing.setEnabled(enabled),
                () -> activations.save(new PluginActivation(pluginId, enabled)));
        enabledCache.put(pluginId, enabled);
        log.info("Plugin '{}' {} by an admin{}", pluginId, enabled ? "enabled" : "disabled",
                enabled ? " — its backend starts at the next restart" : " — it stops serving immediately");
    }

    /** Every admin-set override of one plugin, keyed by field name. Never null. */
    @Transactional(readOnly = true)
    public Map<String, JsonNode> config(String pluginId) {
        return configCache.computeIfAbsent(pluginId, id -> {
            Map<String, JsonNode> loaded = new HashMap<>();
            configValues.findByIdPluginId(id).forEach(v -> loaded.put(v.getId().getKey(), v.getValue()));
            return Map.copyOf(loaded);
        });
    }

    /**
     * Sets or clears one override. A {@code null} (or JSON null) value removes the override, so the field
     * falls back to the manifest default rather than being pinned to an empty value.
     */
    @Transactional
    public void putConfig(String pluginId, String key, JsonNode value) {
        PluginConfigValueKey id = new PluginConfigValueKey(pluginId, key);
        if (value == null || value.isNull()) {
            configValues.deleteById(id);
        } else {
            configValues.findById(id).ifPresentOrElse(
                    existing -> existing.overwrite(value),
                    () -> configValues.save(new PluginConfigValue(id, value)));
        }
        configCache.remove(pluginId);
        // The key, never the value.
        //
        // A config field is exactly where an API token or a webhook secret lives, and the config API gates those
        // by role for that reason. This logger is not a private channel: AppLogAppender persists everything at
        // INFO into `app_log`, where the admin viewer renders it verbatim and its free-text search indexes it,
        // and stdout goes wherever the operator ships container logs. Interpolating the value here handed a
        // role-gated secret to every reader of both. PersonalAccessTokenService takes the same care.
        log.info("Plugin '{}' config: {} {}", pluginId, key,
                value == null || value.isNull() ? "reset to the manifest default" : "set");
    }

    /**
     * Deletes every document a plugin stored in the generic doc store (ARCHITECTURE §7.8). Activation and
     * config survive on purpose: purging data must not silently re-enable a plugin or reset its settings.
     *
     * @return how many documents were removed
     */
    @Transactional
    public int purgeData(String pluginId) {
        int removed = data.deleteByPluginId(pluginId);
        // Irreversible and admin-initiated: worth a permanent record of how much went.
        log.info("Purged {} stored document(s) of plugin '{}'; its config and on/off state were kept",
                removed, pluginId);
        return removed;
    }
}
