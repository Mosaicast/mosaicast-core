// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaicast.core.plugin.PluginManifest.ConfigField;
import dev.mosaicast.plugin.api.PluginConfig;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only {@link PluginConfig} over a plugin's declared config fields (ARCHITECTURE §7.2). In this
 * milestone values are the manifest defaults; admin-set overrides arrive with the config admin UI (E5b).
 */
public class PluginConfigImpl implements PluginConfig {

    private final Map<String, ConfigField> fields;
    private final ObjectMapper objectMapper;

    public PluginConfigImpl(Map<String, ConfigField> fields, ObjectMapper objectMapper) {
        this.fields = fields == null ? Map.of() : fields;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        ConfigField field = fields.get(key);
        if (field == null || field.defaultValue() == null || field.defaultValue().isNull()) {
            return Optional.empty();
        }
        JsonNode value = field.defaultValue();
        return Optional.of(objectMapper.convertValue(value, type));
    }
}
