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
    public static final Set<String> KNOWN_PLACEMENTS =
            Set.of("top", "card", "main", "sidebar", "player", "feed", "site", "admin");

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

    /** A declared config field: its type, default value (raw JSON) and who may edit it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigField(String type, @JsonProperty("default") JsonNode defaultValue, String editableBy) {
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
