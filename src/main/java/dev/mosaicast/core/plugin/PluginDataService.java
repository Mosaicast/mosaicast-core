// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional gateway to the generic per-plugin doc store (ARCHITECTURE §7.6). Both the plugin-facing
 * {@link DocStoreImpl} (backend {@code ctx.store()}) and the HTTP data controller call through here, so
 * scoping, key validation and JSON (de)serialization live in one place. Every method takes the owning
 * {@code pluginId} explicitly — callers bind it; it is never taken from plugin-supplied input.
 */
@Service
public class PluginDataService {

    private static final Pattern KEY = Pattern.compile(DocStore.KEY_PATTERN);

    private final PluginDataRepository repository;
    private final ObjectMapper objectMapper;

    public PluginDataService(PluginDataRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Reads the raw JSON document at {@code (scope, key)}, or empty when absent. */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getRaw(String pluginId, Scope scope, String key) {
        return repository.findById(keyOf(pluginId, scope, key)).map(PluginData::getValue);
    }

    /** Reads a document and coerces it to {@code type}, or empty when absent. */
    @Transactional(readOnly = true)
    public <T> Optional<T> get(String pluginId, Scope scope, String key, Class<T> type) {
        return getRaw(pluginId, scope, key).map(node -> objectMapper.convertValue(node, type));
    }

    /** Upserts a Java value (serialized to JSON) at {@code (scope, key)}, last-write-wins. */
    @Transactional
    public void put(String pluginId, Scope scope, String key, Object value) {
        putRaw(pluginId, scope, key, objectMapper.valueToTree(value));
    }

    /** Upserts a raw JSON document at {@code (scope, key)}, last-write-wins. */
    @Transactional
    public void putRaw(String pluginId, Scope scope, String key, JsonNode value) {
        requireValidKey(key);
        PluginDataKey id = keyOf(pluginId, scope, key);
        repository.findById(id).ifPresentOrElse(
                existing -> existing.overwrite(value),
                () -> repository.save(new PluginData(id, value)));
    }

    /** Removes the document at {@code (scope, key)}; returns whether one existed. Idempotent. */
    @Transactional
    public boolean delete(String pluginId, Scope scope, String key) {
        PluginDataKey id = keyOf(pluginId, scope, key);
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    /** Every document in a scope whose key starts with {@code keyPrefix} (empty matches all). */
    @Transactional(readOnly = true)
    public List<DocEntry> query(String pluginId, Scope scope, String keyPrefix) {
        return repository
                .findInScope(pluginId, scope.type().name().toLowerCase(), scope.id(), keyPrefix)
                .stream()
                .map(d -> new DocEntry(d.getId().getKey(), d.getValue()))
                .toList();
    }

    /** Paginated slice of {@link #query} for the HTTP list endpoint. */
    @Transactional(readOnly = true)
    public Page<DocEntry> queryPage(String pluginId, Scope scope, String keyPrefix, Pageable pageable) {
        return repository
                .pageInScope(pluginId, scope.type().name().toLowerCase(), scope.id(), keyPrefix, pageable)
                .map(d -> new DocEntry(d.getId().getKey(), d.getValue()));
    }

    /**
     * @throws IllegalArgumentException if {@code key} does not match {@link DocStore#KEY_PATTERN}
     */
    public static void requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid doc key: " + key);
        }
    }

    private static PluginDataKey keyOf(String pluginId, Scope scope, String key) {
        return new PluginDataKey(pluginId, scope.type().name().toLowerCase(), scope.id(), key);
    }
}
