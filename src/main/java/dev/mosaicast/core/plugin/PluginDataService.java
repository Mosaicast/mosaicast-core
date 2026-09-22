// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
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
    private final PluginSettingsService settings;
    private final ObjectMapper objectMapper;
    private final ScopeIds scopeIds;

    public PluginDataService(PluginDataRepository repository, PluginSettingsService settings,
                             ObjectMapper objectMapper, ScopeIds scopeIds) {
        this.repository = repository;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.scopeIds = scopeIds;
    }

    /** Reads the raw JSON document at {@code (scope, key)}, or empty when absent. */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getRaw(String pluginId, DataScope scope, String key) {
        return repository.findById(keyOf(pluginId, scope, key)).map(PluginData::getValue);
    }

    /** {@link #getRaw} for a plugin-supplied {@link Scope}. */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getRaw(String pluginId, Scope scope, String key) {
        return getRaw(pluginId, DataScope.of(scope), key);
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
    /** {@link #putRaw} for a plugin-supplied {@link Scope}. */
    @Transactional
    public void putRaw(String pluginId, Scope scope, String key, JsonNode value) {
        putRaw(pluginId, DataScope.of(scope), key, value);
    }

    /** Upserts a raw JSON document at a resolved scope, last-write-wins. */
    @Transactional
    public void putRaw(String pluginId, DataScope scope, String key, JsonNode value) {
        requireWritable(pluginId);
        requireValidKey(key);
        PluginDataKey id = keyOf(pluginId, scope, key);
        repository.findById(id).ifPresentOrElse(
                existing -> existing.overwrite(value),
                () -> repository.save(new PluginData(id, value)));
    }

    /** Removes the document at {@code (scope, key)}; returns whether one existed. Idempotent. */
    /** {@link #delete} for a plugin-supplied {@link Scope}. */
    @Transactional
    public boolean delete(String pluginId, Scope scope, String key) {
        return delete(pluginId, DataScope.of(scope), key);
    }

    /** Removes the document at a resolved scope; returns whether one existed. Idempotent. */
    @Transactional
    public boolean delete(String pluginId, DataScope scope, String key) {
        requireWritable(pluginId);
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
                .findInScope(pluginId, scope.type().name().toLowerCase(Locale.ROOT), scopeIds.canonical(scope), keyPrefix)
                .stream()
                .map(d -> new DocEntry(d.getId().getKey(), d.getValue()))
                .toList();
    }

    /**
     * Every user's documents under {@code keyPrefix}, across all {@code USER} partitions of one plugin.
     *
     * <p>The one read in this class that crosses an owner boundary, so it is deliberately awkward to reach:
     * no HTTP endpoint maps to it, and {@link DocStoreImpl} exposes it only to a plugin's own backend. That
     * is what keeps it from being an IDOR — a visitor's request cannot arrive here, whatever it asks for.
     *
     * <p>It exists because the alternative is worse. Without it, per-user data is readable only by its owner
     * and a leaderboard or moderation view becomes impossible, which pushes plugins into having each browser
     * report a summary of itself into a shared scope — a number the client can simply make up. An aggregate
     * computed on the server from the real partitions is the only version that is true.
     *
     * <p>The owner id is read from the partition, never supplied by a caller.
     */
    @Transactional(readOnly = true)
    public List<OwnedDocEntry> queryAcrossUsers(String pluginId, String keyPrefix) {
        String prefix = keyPrefix == null ? "" : keyPrefix;
        return repository
                .findInScopeType(pluginId, DataScope.USER_TYPE, prefix)
                .stream()
                .map(d -> new OwnedDocEntry(
                        UUID.fromString(d.getId().getScopeId()), d.getId().getKey(), d.getValue()))
                .toList();
    }

    /** Paginated slice of {@link #query} for the HTTP list endpoint. */
    @Transactional(readOnly = true)
    public Page<DocEntry> queryPage(String pluginId, DataScope scope, String keyPrefix, Pageable pageable) {
        return repository
                .pageInScope(pluginId, scope.typeColumn(), canonical(scope), keyPrefix, pageable)
                .map(d -> new DocEntry(d.getId().getKey(), d.getValue()));
    }

    /**
     * Refuses writes from a switched-off plugin (ARCHITECTURE §7.8). The HTTP surface already 404s for a
     * disabled plugin, but a plugin's own backend keeps running in-process until the next restart — a thread
     * or scheduled task it started must not keep mutating the store after an admin switched it off. Reads are
     * left open: they change nothing, and a disabled plugin's data stays intact until an explicit purge.
     *
     * @throws AccessDeniedException if the plugin is disabled
     */
    private void requireWritable(String pluginId) {
        if (!settings.enabled(pluginId)) {
            throw new AccessDeniedException("Plugin is disabled: " + pluginId);
        }
    }

    /**
     * @throws IllegalArgumentException if {@code key} does not match {@link DocStore#KEY_PATTERN}
     */
    /** Whether this plugin has ever stored a document — see {@code AccountErasureService} for why. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean hasStoredData(String pluginId) {
        return repository.existsByIdPluginId(pluginId);
    }

    /**
     * Drops every plugin's documents in one user's partition — the host-owned half of an account deletion
     * (§12). A plugin's own tables and files are its own to erase; see {@code UserDataHandler}.
     *
     * @return how many documents were removed
     */
    @org.springframework.transaction.annotation.Transactional
    public int deleteUserScope(String userId) {
        return repository.deleteUserScope(DataScope.USER_TYPE, userId);
    }

    public static void requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid doc key: " + key);
        }
    }

    /**
     * Builds the primary key, canonicalising the scope id first.
     *
     * <p>Feed and episode scope ids became public slugs, and {@code FeedAccessImpl} resolves either form —
     * so a plugin holding an old UUID still gets the right episode list and nothing looks broken, while this
     * partitioned on the raw string and sent its reads to an empty partition and its writes to a second one.
     * Silent, and indistinguishable from data loss.
     *
     * <p>{@link PluginScopeRepartition} moves the rows that already exist; this stops new ones diverging.
     * Both are needed: the sweep cannot fix a document written after it ran, and canonicalising cannot
     * relocate a document written before.
     */
    private PluginDataKey keyOf(String pluginId, DataScope scope, String key) {
        return new PluginDataKey(pluginId, scope.typeColumn(), canonical(scope), key);
    }

    /**
     * The partition id to key on.
     *
     * <p>A {@code USER} partition is already the host-resolved user id — there is nothing to alias, and
     * running it through the slug resolver would be looking up a feed by a user's UUID.
     */
    private String canonical(DataScope scope) {
        return scope.type() == ScopeType.USER
                ? scope.partitionId()
                : scopeIds.canonical(new Scope(scope.type(), scope.partitionId()));
    }
}
