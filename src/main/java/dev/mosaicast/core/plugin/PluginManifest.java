// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.mosaicast.plugin.api.PlatformApi;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed {@code plugin.json} manifest of a plugin (ARCHITECTURE §7.2). Deserialized from the plugin
 * folder at load time; unknown fields are tolerated for forward compatibility.
 *
 * <p>Validation ({@link #validate()}) is the stability anchor of the plugin system: an incompatible
 * {@code platformApi}, a declared relational {@code schema} (deferred to a later milestone, §7.6), or a
 * slot targeting an unknown region is rejected at load — the plugin is disabled, not fatal (§7.8).
 *
 * @param id          the plugin id (its folder name and HTTP namespace); never blank
 * @param version     the plugin's own version
 * @param platformApi the plugin-contract version the plugin was built against (must be compatible with
 *                    {@link PlatformApi#VERSION})
 * @param name        the human-readable plugin name
 * @param backend     backend entry points (PF4J extension classes)
 * @param frontend    frontend bundle entry + custom-element tags
 * @param slots       where the plugin mounts its elements in the shell
 * @param storage     {@code "doc"} (generic doc store, the v1 default) or {@code "schema"} (deferred)
 * @param config      declared config fields, keyed by field name
 * @param consent     declared consent categories / external sources
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
        String id,
        String version,
        String platformApi,
        String name,
        Backend backend,
        Frontend frontend,
        List<Slot> slots,
        String storage,
        Map<String, ConfigField> config,
        Consent consent) {

    /** Storage kinds a manifest may declare. */
    public static final String STORAGE_DOC = "doc";
    public static final String STORAGE_SCHEMA = "schema";

    /** The slot placements the shell defines (ARCHITECTURE §7.3); a slot targeting anything else is rejected. */
    /**
     * The full-page region behind a plugin deep link {@code /p/{pluginId}/…} (§6.4). A plugin opts in by
     * declaring a slot here; the host reserves the route either way and hands the subpath to the element as
     * {@code ctx.route}.
     */
    public static final String PLACEMENT_PAGE = "page";

    public static final Set<String> KNOWN_PLACEMENTS =
            Set.of("top", "card", "main", "sidebar", "player", "feed", "site", "admin", PLACEMENT_PAGE);

    /** Config field types the generated admin form can render and type-check (§7.2). */
    public static final String CONFIG_TYPE_STRING = "string";
    public static final String CONFIG_TYPE_NUMBER = "number";
    public static final String CONFIG_TYPE_BOOLEAN = "boolean";
    public static final Set<String> KNOWN_CONFIG_TYPES =
            Set.of(CONFIG_TYPE_STRING, CONFIG_TYPE_NUMBER, CONFIG_TYPE_BOOLEAN);

    /** Roles a config field may be delegated to; anything else (including a fan) is rejected (§8.5). */
    public static final String EDITABLE_BY_ADMIN = "admin";
    public static final String EDITABLE_BY_PODCASTER = "podcaster";
    public static final Set<String> KNOWN_EDITABLE_BY = Set.of(EDITABLE_BY_ADMIN, EDITABLE_BY_PODCASTER);

    /** Backend entry points. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Backend(String basePath, List<String> extensions) {
    }

    /** Frontend bundle: the ES entry file (served from the plugin's {@code assets/}) and its element tags. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Frontend(String entry, List<String> elements) {
    }

    /**
     * One mount point: {@code element} rendered at {@code placement} for the given {@code scope}, visible to
     * users at or above {@code visibleTo}, ordered by {@code order} within the region (ties broken by id).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slot(String scope, String element, String placement, String visibleTo, Integer order) {
    }

    /**
     * A declared config field: its type, default value (raw JSON) and who may edit it (ARCHITECTURE §7.2).
     * The host renders these as a generic admin form — plugins never build their own config UI — so the
     * declaration has to carry enough to render and validate an input.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigField(String type, @JsonProperty("default") JsonNode defaultValue, String editableBy) {

        /** The role a field defaults to when the manifest names none: the most restrictive one. */
        public String editableByOrDefault() {
            return editableBy == null || editableBy.isBlank() ? EDITABLE_BY_ADMIN : editableBy.toLowerCase();
        }

        /**
         * Whether {@code value} is a legal setting for this field. JSON null always passes: it is how an
         * admin clears an override and falls back to the manifest default.
         */
        public boolean accepts(JsonNode value) {
            if (value == null || value.isNull()) {
                return true;
            }
            return switch (type == null ? "" : type.toLowerCase()) {
                case CONFIG_TYPE_STRING -> value.isTextual();
                case CONFIG_TYPE_NUMBER -> value.isNumber();
                case CONFIG_TYPE_BOOLEAN -> value.isBoolean();
                default -> false;
            };
        }
    }

    /** Declared consent surface (empty lists mean no cookie banner / no third-party sources). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consent(List<String> categories, List<String> externalSources) {
    }

    /**
     * Validates the manifest against the host contract, throwing {@link PluginValidationException} on the
     * first problem. Called at load time; a throw records the plugin as rejected with the message as reason.
     *
     * @throws PluginValidationException if the manifest is unusable or incompatible
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new PluginValidationException("manifest has no id");
        }
        if (platformApi == null || !isPlatformApiCompatible(platformApi)) {
            throw new PluginValidationException(
                    "platformApi %s is incompatible with host %s".formatted(platformApi, PlatformApi.VERSION));
        }
        if (STORAGE_SCHEMA.equalsIgnoreCase(storage)) {
            throw new PluginValidationException(
                    "schema storage is not available in this version (ARCHITECTURE §7.6)");
        }
        if (slots != null) {
            for (Slot slot : slots) {
                if (slot.placement() == null || !KNOWN_PLACEMENTS.contains(slot.placement())) {
                    throw new PluginValidationException("unknown slot placement: " + slot.placement());
                }
            }
        }
        validateConfig();
    }

    /**
     * The host renders declared config fields as a form and type-checks admin input against them, so a field
     * it cannot render or check is rejected at load rather than surfacing as a broken admin page.
     */
    private void validateConfig() {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, ConfigField> entry : config.entrySet()) {
            ConfigField field = entry.getValue();
            String type = field.type() == null ? null : field.type().toLowerCase();
            if (type == null || !KNOWN_CONFIG_TYPES.contains(type)) {
                throw new PluginValidationException(
                        "config field '%s' has unknown type: %s".formatted(entry.getKey(), field.type()));
            }
            if (!KNOWN_EDITABLE_BY.contains(field.editableByOrDefault())) {
                throw new PluginValidationException(
                        "config field '%s' has unknown editableBy: %s".formatted(entry.getKey(), field.editableBy()));
            }
            if (!field.accepts(field.defaultValue())) {
                throw new PluginValidationException(
                        "config field '%s' default does not match declared type %s".formatted(entry.getKey(), type));
            }
        }
    }

    /**
     * A plugin is compatible when it declares the same major and minor as the host contract. Pre-1.0 the
     * minor carries breaking changes, so an exact {@code major.minor} match is required (patch is free).
     */
    private static boolean isPlatformApiCompatible(String declared) {
        int[] want = majorMinor(PlatformApi.VERSION);
        int[] got = majorMinor(declared);
        return got != null && want != null && got[0] == want[0] && got[1] == want[1];
    }

    private static int[] majorMinor(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
