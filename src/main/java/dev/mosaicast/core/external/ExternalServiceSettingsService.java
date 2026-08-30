// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import dev.mosaicast.core.external.settings.SecretBox;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsFieldType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * Reads and writes the provider choice and its settings (ARCHITECTURE §12.7).
 *
 * <p>Follows {@code PluginSettingsService} closely and on purpose: the same problem — small values, read on
 * every call, written rarely by one admin — gets the same solution. Two {@link ConcurrentHashMap} caches,
 * invalidated on write, per instance.
 *
 * <p><strong>The log line names the key, never the value.</strong> Verbatim the precedent from plugin config,
 * and it matters here even for non-secrets: a self-hosted provider's base URL is internal topology, and
 * {@code AppLogAppender} persists INFO into {@code app_log}, which the admin viewer renders and free-text
 * search indexes.
 */
@Service
public class ExternalServiceSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceSettingsService.class);

    private final ExternalServiceSelectionRepository selections;
    private final ExternalProviderSettingRepository settings;
    private final SecretBox secretBox;

    /** kind id → selected provider id (or an empty marker for "explicitly none"). */
    private final Map<String, Optional<String>> selectionCache = new ConcurrentHashMap<>();

    /** "kind/provider" → the admin's overrides. */
    private final Map<String, Map<String, JsonNode>> settingsCache = new ConcurrentHashMap<>();

    public ExternalServiceSettingsService(ExternalServiceSelectionRepository selections,
                                          ExternalProviderSettingRepository settings,
                                          SecretBox secretBox) {
        this.selections = selections;
        this.settings = settings;
        this.secretBox = secretBox;
    }

    /**
     * The selected provider for a kind, or empty when there is none.
     *
     * <p>Empty covers both "never chosen" and "explicitly none" — the distinction is history, and a caller
     * about to make an outbound call does not act differently on it.
     */
    @Transactional(readOnly = true)
    public Optional<String> selected(ExternalServiceKind kind) {
        return selectionCache.computeIfAbsent(kind.id(), id -> selections.findById(id)
                .map(ExternalServiceSelection::getProviderId)
                .filter(provider -> provider != null && !provider.isBlank()));
    }

    /** Selects a provider, or {@code null} for none. */
    @Transactional
    public void select(ExternalServiceKind kind, String providerId) {
        String cleaned = providerId == null || providerId.isBlank() ? null : providerId.trim();
        ExternalServiceSelection row = selections.findById(kind.id())
                .orElseGet(() -> new ExternalServiceSelection(kind, cleaned));
        row.setProviderId(cleaned);
        selections.save(row);
        selectionCache.remove(kind.id());
        log.info("External service '{}' provider set to {}", kind.id(), cleaned == null ? "none" : cleaned);
    }

    /** The admin's overrides for one provider, sealed values included as stored. */
    @Transactional(readOnly = true)
    public Map<String, JsonNode> overrides(ExternalServiceKind kind, String providerId) {
        return settingsCache.computeIfAbsent(cacheKey(kind, providerId), ignored -> {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            settings.findByIdKindAndIdProviderId(kind.id(), providerId)
                    .forEach(row -> values.put(row.getId().getKey(), row.getValue()));
            return Map.copyOf(values);
        });
    }

    /**
     * Sets or clears one override.
     *
     * <p>A JSON null deletes the row so the descriptor's default applies again. A {@code SECRET} is sealed on
     * the way in; every other value is stored as given, already type-checked against its declared field.
     *
     * @param field the declared field — needed to know whether the value is a credential
     */
    @Transactional
    public void put(ExternalServiceKind kind, String providerId, SettingsField field, JsonNode value) {
        ExternalProviderSettingKey id = new ExternalProviderSettingKey(kind, providerId, field.key());
        if (value == null || value.isNull()) {
            settings.deleteById(id);
            settingsCache.remove(cacheKey(kind, providerId));
            log.info("External provider '{}/{}' setting '{}' cleared", kind.id(), providerId, field.key());
            return;
        }
        JsonNode stored = field.type() == SettingsFieldType.SECRET
                ? StringNode.valueOf(secretBox.seal(value.stringValue()))
                : value;
        settings.findById(id).ifPresentOrElse(
                row -> {
                    row.setValue(stored);
                    settings.save(row);
                },
                () -> settings.save(new ExternalProviderSetting(id, stored)));
        settingsCache.remove(cacheKey(kind, providerId));
        // The key, never the value: this is exactly where an API token lives, and INFO is persisted.
        log.info("External provider '{}/{}' setting '{}' updated", kind.id(), providerId, field.key());
    }

    /** Unseals a stored credential. Package-private: only the config resolution path has business here. */
    String openSecret(JsonNode stored) {
        return stored == null || !stored.isString() ? null : secretBox.open(stored.stringValue());
    }

    /** Whether stored credentials are actually encrypted, for the admin page to be honest about it. */
    public boolean encryptingSecrets() {
        return secretBox.encrypting();
    }

    private static String cacheKey(ExternalServiceKind kind, String providerId) {
        return kind.id() + "/" + providerId;
    }
}
