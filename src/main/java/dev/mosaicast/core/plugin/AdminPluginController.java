// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.plugin.PluginManifest.ConfigField;
import dev.mosaicast.core.plugin.PluginManifest.Consent;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin surface of the plugin system (ARCHITECTURE §7.2, §7.8, §8.5): what was discovered and in which
 * state, the generated config form's data, the activation toggle, and the explicit "purge plugin data".
 *
 * <p>This controller owns the <em>declaration-level</em> rules, because it is the only place that has both
 * the manifest and the stored settings: a field must be declared to be settable, its value must match the
 * declared type, and {@code editableBy} decides which role may set it. {@link PluginSettingsService} below it
 * only persists.
 *
 * <p>Everything here is ADMIN via the {@code /api/admin/**} rule, except the config endpoint, which
 * {@code SecurityConfig} also opens to PODCASTER so a podcaster can edit the fields delegated to them.
 */
@RestController
public class AdminPluginController {

    private final PluginLoaderService plugins;
    private final PluginSettingsService settings;

    public AdminPluginController(PluginLoaderService plugins, PluginSettingsService settings) {
        this.plugins = plugins;
        this.settings = settings;
    }

    @GetMapping("/api/admin/plugins")
    public List<AdminPlugin> list() {
        return plugins.all().stream().map(this::toAdminPlugin).toList();
    }

    /**
     * Switches a plugin on or off. Off takes effect immediately for every host-mediated surface (manifest,
     * doc-store API, assets, scheduler, doc-store writes); the plugin's already-started backend stays in
     * process until the next restart, where the loader skips it entirely (§7.8).
     */
    @PutMapping("/api/admin/plugins/{id}/enabled")
    public AdminPlugin setEnabled(@PathVariable String id, @RequestParam boolean value) {
        PluginRegistration registration = registrationOf(id);
        settings.setEnabled(id, value);
        return toAdminPlugin(registration);
    }

    /**
     * Sets or clears config overrides. The body maps field name → value; a JSON {@code null} clears the
     * override so the manifest default applies again. Unknown fields are a 400, a value of the wrong type is
     * a 400, and a field the caller's role may not edit is a 403 — all-or-nothing, so a rejected field never
     * leaves a half-applied form behind.
     */
    @PutMapping("/api/admin/plugins/{id}/config")
    public AdminPlugin setConfig(@PathVariable String id, @RequestBody Map<String, JsonNode> values,
                                 Authentication authentication) {
        PluginRegistration registration = registrationOf(id);
        Map<String, ConfigField> declared = declaredConfig(registration);
        Optional<Role> role = CurrentUser.role(authentication);

        values.forEach((key, value) -> {
            ConfigField field = declared.get(key);
            if (field == null) {
                throw new IllegalArgumentException("Plugin '%s' declares no config field '%s'".formatted(id, key));
            }
            if (!mayEdit(field, role)) {
                throw new AccessDeniedException(
                        "Config field '%s' is editable by %s only".formatted(key, field.editableByOrDefault()));
            }
            if (!field.accepts(value)) {
                throw new IllegalArgumentException(
                        "Config field '%s' expects a %s".formatted(key, field.type()));
            }
        });
        values.forEach((key, value) -> settings.putConfig(id, key, value));
        return toAdminPlugin(registration);
    }

    /**
     * Removes everything the plugin stored in the generic doc store (§7.8). Deliberately explicit and
     * irreversible: deleting a plugin folder only makes it dormant, its data survives until an admin asks
     * for this. Activation and config are host settings and are kept.
     */
    @PostMapping("/api/admin/plugins/{id}/purge")
    public ResponseEntity<PurgeResult> purge(@PathVariable String id) {
        registrationOf(id);
        return ResponseEntity.ok(new PurgeResult(settings.purgeData(id)));
    }

    private AdminPlugin toAdminPlugin(PluginRegistration r) {
        PluginManifest manifest = r.manifest();
        Map<String, JsonNode> overrides = settings.config(r.id());
        Map<String, AdminConfigField> config = new LinkedHashMap<>();
        declaredConfig(r).forEach((key, field) -> config.put(key, new AdminConfigField(
                field.type(),
                field.editableByOrDefault(),
                field.defaultValue(),
                overrides.getOrDefault(key, field.defaultValue()),
                overrides.containsKey(key))));
        return new AdminPlugin(
                r.id(),
                r.status().name(),
                r.reason(),
                manifest == null ? null : manifest.name(),
                manifest == null ? null : manifest.version(),
                settings.enabled(r.id()),
                config,
                manifest == null ? null : manifest.consent());
    }

    private static Map<String, ConfigField> declaredConfig(PluginRegistration r) {
        if (r.manifest() == null || r.manifest().config() == null) {
            return Map.of();
        }
        return r.manifest().config();
    }

    /** ADMIN may edit every field; PODCASTER only the ones delegated to them. */
    private static boolean mayEdit(ConfigField field, Optional<Role> role) {
        return role.map(r -> r == Role.ADMIN
                        || (r == Role.PODCASTER
                            && PluginManifest.EDITABLE_BY_PODCASTER.equals(field.editableByOrDefault())))
                .orElse(false);
    }

    private PluginRegistration registrationOf(String id) {
        return plugins.registration(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
    }

    /**
     * One plugin's discovery outcome plus its host settings.
     *
     * @param enabled whether it is switched on; a {@code DISABLED} status means it was already off at boot,
     *                while {@code LOADED} + {@code enabled=false} means it was switched off since
     */
    public record AdminPlugin(String id, String status, String reason, String name, String version,
                              boolean enabled, Map<String, AdminConfigField> config, Consent consent) {
    }

    /** One declared config field, its default and the value currently in effect — the form's row model. */
    public record AdminConfigField(String type, String editableBy, JsonNode defaultValue,
                                   JsonNode value, boolean overridden) {
    }

    /** How many documents a purge removed. */
    public record PurgeResult(int purged) {
    }
}
