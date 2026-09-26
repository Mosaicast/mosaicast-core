// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.mosaicast.core.plugin.PluginManifest.ConfigField;
import dev.mosaicast.plugin.api.PluginConfig;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only {@link PluginConfig} over a plugin's declared config fields (ARCHITECTURE §7.2). Resolution order
 * is <strong>admin override → manifest default → empty</strong>: only fields the manifest declares are
 * readable at all, so a stale override left over from an older manifest can never surface as config.
 *
 * <p>Overrides are read through {@link PluginSettingsService} on every call (it caches them), so an admin
 * edit takes effect without restarting the host. Plugins stay read-only by contract — they never write config.
 */
public class PluginConfigImpl implements PluginConfig {

    private final String pluginId;
    private final Map<String, ConfigField> fields;
    private final PluginSettingsService settings;
    private final ObjectMapper objectMapper;

    public PluginConfigImpl(String pluginId, Map<String, ConfigField> fields,
                            PluginSettingsService settings, ObjectMapper objectMapper) {
        this.pluginId = pluginId;
        this.fields = fields == null ? Map.of() : fields;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        ConfigField field = fields.get(key);
        if (field == null) {
            return Optional.empty();
        }
        JsonNode value = settings.config(pluginId).get(key);
        // A stored value that breaks a bound declared after it was saved counts as unset (SDK 0.16.0): the
        // contract promises a plugin that what it reads here satisfies its declaration, so it needs no clamp.
        if (value == null || value.isNull() || !field.accepts(value)) {
            value = field.defaultValue();
        }
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.convertValue(value, type));
    }
}
