// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads {@code storage} in either shape the manifest allows — the string {@code "doc"} or an object
 * carrying a {@code schema} declaration (ARCHITECTURE §7.2/§7.6).
 *
 * <p>Deliberately tolerant, because it is not the gate. A shape this cannot make sense of becomes a doc
 * declaration and reaches {@link PluginManifest#validate()}, which is where a plugin is refused with a
 * reason an author can act on. A deserializer that threw would surface the same problem as a Jackson
 * message about a token type, attributed to no plugin in particular.
 */
public class PluginStorageDeserializer extends ValueDeserializer<PluginStorage> {

    @Override
    public PluginStorage deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        return PluginStorage.fromNode(node);
    }
}
