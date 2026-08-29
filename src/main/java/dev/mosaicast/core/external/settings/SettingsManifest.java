// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything one provider needs configured (ARCHITECTURE §12.7).
 *
 * <p>Declared in Java rather than parsed from JSON, because providers are compile-time beans that ship with
 * the host — there is no untrusted author here and nothing to sandbox, so the manifest may as well be code
 * the compiler checks.
 */
public record SettingsManifest(List<SettingsField> fields) {

    public SettingsManifest {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static SettingsManifest of(SettingsField... fields) {
        return new SettingsManifest(List.of(fields));
    }

    public static SettingsManifest empty() {
        return new SettingsManifest(List.of());
    }

    public Optional<SettingsField> field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst();
    }

    /** The fields that actually hold a stored value — everything an admin form submits. */
    public List<SettingsField> storedFields() {
        return fields.stream().filter(field -> field.type().isStored()).toList();
    }

    /**
     * Checks a whole submitted map at once.
     *
     * <p>All-or-nothing on purpose, the same rule {@code AdminPluginController} applies to plugin config:
     * a half-applied form leaves a provider in a state the admin did not ask for and cannot see, which is
     * worse than a rejected one. Every bad field is reported, not just the first, so the form can annotate
     * every row in one round trip.
     *
     * @param values the submitted values, keyed by field
     * @return one entry per problem, in field order; empty when the submission is good
     */
    public List<FieldError> validate(Map<String, ? extends tools.jackson.databind.JsonNode> values) {
        List<FieldError> problems = new ArrayList<>();
        values.forEach((key, value) -> {
            Optional<SettingsField> declared = field(key);
            if (declared.isEmpty()) {
                problems.add(new FieldError(key, "is not a setting this provider declares"));
                return;
            }
            if (declared.get().accepts(value) instanceof SettingsField.Result.Rejected rejected) {
                problems.add(new FieldError(key, rejected.reason()));
            }
        });
        return List.copyOf(problems);
    }

    /**
     * One rejected field.
     *
     * @param key    the field
     * @param reason English, and names the constraint — never echoes the submitted value, which for a
     *               {@link SettingsFieldType#SECRET} would put a credential in an error body and a log
     */
    public record FieldError(String key, String reason) {
    }

    /** The declared defaults, for a config view to fall back to. Fields without one are absent. */
    public Map<String, tools.jackson.databind.JsonNode> defaults() {
        Map<String, tools.jackson.databind.JsonNode> defaults = new LinkedHashMap<>();
        for (SettingsField field : fields) {
            if (field.defaultValue() != null) {
                defaults.put(field.key(), field.defaultValue());
            }
        }
        return Map.copyOf(defaults);
    }
}
