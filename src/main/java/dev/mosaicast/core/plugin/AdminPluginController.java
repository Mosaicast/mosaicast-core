// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
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
    private final PluginBlobService blobs;
    private final PluginBlobProperties blobProperties;

    public AdminPluginController(PluginLoaderService plugins, PluginSettingsService settings,
                                 PluginBlobService blobs, PluginBlobProperties blobProperties) {
        this.plugins = plugins;
        this.settings = settings;
        this.blobs = blobs;
        this.blobProperties = blobProperties;
    }

    @GetMapping("/api/admin/plugins")
    public List<AdminPlugin> list(Authentication authentication) {
        Optional<Role> role = CurrentUser.role(authentication);
        return plugins.all().stream().map(r -> toAdminPlugin(r, role)).toList();
    }

    /**
     * Switches a plugin on or off.
     *
     * <p>Off takes effect immediately for every host-mediated surface (manifest, doc-store API, assets,
     * scheduler, doc-store writes); the plugin's already-started backend stays in process until the next
     * restart, where the loader skips it entirely (§7.8).
     *
     * <p>On is the asymmetric half, and it used to do nothing for a plugin that had been off at boot: the
     * loader never ran {@code loadPlugin}/{@code startPlugin}/{@code register(ctx)} for it, so it stayed
     * {@code DISABLED} and every surface kept answering as though it were not installed — while this
     * endpoint returned 200 and the page showed it enabled (core#182). Enabling now runs the boot path for
     * it, so it ends up in the state it would have been in had it been on at startup.
     */
    @PutMapping("/api/admin/plugins/{id}/enabled")
    public AdminPlugin setEnabled(@PathVariable String id, @RequestParam boolean value,
                                  Authentication authentication) {
        registrationOf(id);
        settings.setEnabled(id, value);
        if (value) {
            plugins.loadIfSwitchedOn(id);
        }
        // Re-read: loading replaces the registration, and returning the one captured before would report
        // the state the caller just changed away from.
        return toAdminPlugin(registrationOf(id), CurrentUser.role(authentication));
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
                // A closed set fails this for a different reason than a type mismatch, and "expects a
                // string" for a word that is a string tells the operator nothing about what to do.
                throw new IllegalArgumentException(field.isEnum()
                        ? "Config field '%s' expects one of its declared options".formatted(key)
                        : "Config field '%s' expects a %s".formatted(key, field.type()));
            }
        });
        values.forEach((key, value) -> settings.putConfig(id, key, value));
        return toAdminPlugin(registration, role);
    }

    /**
     * Sets or clears a plugin's storage limits (ARCHITECTURE §11.1).
     *
     * <p><strong>ADMIN only</strong>, unlike {@code /config}, which is open to PODCASTER so per-field
     * delegation works. Disk is a property of the installation rather than of the show: a podcaster deciding
     * how many gigabytes a plugin may occupy is deciding about someone else's server.
     *
     * <p>Either value may be {@code null} to clear that limit back to the manifest's ask and then to the
     * operator's default; clearing both removes the decision entirely. Values are clamped to the operator's
     * hard ceilings, so the response reports what is actually in force rather than what was typed.
     *
     * @param id      the plugin
     * @param request the limits to grant
     * @return the plugin's updated admin view
     */
    @PutMapping("/api/admin/plugins/{id}/blob-limits")
    public AdminPlugin setBlobLimits(@PathVariable String id, @RequestBody BlobLimitRequest request,
                                     Authentication authentication) {
        PluginRegistration registration = registrationOf(id);
        if (!registration.manifest().declaresBlobs()) {
            throw new IllegalArgumentException(
                    "Plugin '%s' declares no file storage, so it has no limits to set".formatted(id));
        }
        blobs.grant(id, request.quotaBytes(), request.maxFileBytes());
        return toAdminPlugin(registration, CurrentUser.role(authentication));
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

    /**
     * Builds the admin view of a plugin, showing only the config values the caller may actually edit.
     *
     * <p>The redaction lives here rather than at a call site because this is the one place the values are
     * assembled, and {@code /config} is open to PODCASTER so that per-field delegation works ({@code editableBy},
     * §7.2). Writing was always gated correctly; <em>reading back</em> was not, so a podcaster who submitted a
     * field they owned — or an empty body, which validates vacuously — received every admin-only value in the
     * response, API tokens included. A field the caller may not edit keeps its shape (the UI still renders the
     * row and can say who owns it) and loses both its current value and its manifest default. For an ADMIN
     * {@code mayEdit} is always true, so nothing is withheld from the role that already sees everything.
     */
    private AdminPlugin toAdminPlugin(PluginRegistration r, Optional<Role> role) {
        PluginManifest manifest = r.manifest();
        Map<String, JsonNode> overrides = settings.config(r.id());
        Map<String, AdminConfigField> config = new LinkedHashMap<>();
        declaredConfig(r).forEach((key, field) -> {
            boolean visible = mayEdit(field, role);
            config.put(key, new AdminConfigField(
                    field.type(),
                    field.editableByOrDefault(),
                    visible ? field.defaultValue() : null,
                    visible ? overrides.getOrDefault(key, field.defaultValue()) : null,
                    overrides.containsKey(key),
                    // Options, label and description are the shape of the input, not a value, so they are
                    // not withheld from a caller who may only look: the row is rendered either way, a
                    // select that has lost its choices renders as an empty box, and a row whose label went
                    // with the value would leave a podcaster reading an identifier they cannot act on.
                    field.optionsOrEmpty(),
                    field.label(),
                    field.description()));
        });
        return new AdminPlugin(
                r.id(),
                r.status().name(),
                r.reason(),
                manifest == null ? null : manifest.name(),
                manifest == null ? null : manifest.version(),
                settings.enabled(r.id()),
                config,
                manifest == null ? null : manifest.consent(),
                blobsOf(manifest, role),
                externalOf(manifest));
    }

    /**
     * What the plugin declared about the instance's external services, or {@code null} when it declared none.
     *
     * <p>Unredacted, like {@code consent} beside it and unlike {@code blobs}: this is the manifest's own text
     * rather than anything about the install, and §16 puts the point of the declaration exactly here — what a
     * plugin may spend should be readable by whoever is deciding to run it. The floor is resolved to its
     * effective value, so an admin reads what the host will enforce rather than what the file left out.
     */
    private static AdminExternal externalOf(PluginManifest manifest) {
        if (manifest == null || !manifest.declaresExternal()) {
            return null;
        }
        return new AdminExternal(manifest.external().kindsOrEmpty(), manifest.externalUsedBy());
    }

    /**
     * The storage panel's model, or {@code null} when there is nothing to show.
     *
     * <p>Null for a plugin that declares no {@code blobs} block — there is no surface to size — and null for
     * a PODCASTER, who cannot edit these and has no use for the install's disk numbers. That mirrors how
     * config values are redacted above rather than hidden by the client.
     */
    private AdminBlobs blobsOf(PluginManifest manifest, Optional<Role> role) {
        if (manifest == null || !manifest.declaresBlobs() || !role.filter(r -> r == Role.ADMIN).isPresent()) {
            return null;
        }
        Optional<PluginBlobGrant> grant = blobs.grantOf(manifest.id());
        return new AdminBlobs(
                blobs.usedBytes(manifest.id()),
                blobs.count(manifest.id()),
                blobs.effectiveQuotaBytes(manifest),
                blobs.effectiveMaxFileBytes(manifest),
                grant.map(g -> g.getQuotaBytes() != null).orElse(false),
                grant.map(g -> g.getMaxFileBytes() != null).orElse(false),
                manifest.blobs().quotaBytes(),
                manifest.blobs().maxFileBytes(),
                blobProperties.hardQuotaBytes(),
                blobProperties.hardMaxFileBytes());
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
                              boolean enabled, Map<String, AdminConfigField> config, Consent consent,
                              AdminBlobs blobs, AdminExternal external) {
    }

    /**
     * The external services a plugin declared it uses (§16), with the role floor resolved.
     *
     * @param kinds  the declared kinds, lower-cased
     * @param usedBy the lowest role that may set off a call from the plugin's UI — the effective value, so
     *               a manifest that omitted it reads as {@code podcaster} here, which is what the host
     *               enforces
     */
    public record AdminExternal(java.util.List<String> kinds, String usedBy) {
    }

    /**
     * One declared config field, its default and the value currently in effect — the form's row model.
     *
     * <p>{@code options} is empty for a free-form field and non-empty for a closed set, which the form
     * renders as a select. Each option's label is passed through exactly as the manifest wrote it — a
     * plain string, or an object keyed by locale — and resolved in the browser, against the language the
     * operator is reading in. {@code label} and {@code description} are the field's own prose and take the
     * same two shapes; they are what the form shows in place of the raw identifier.
     */
    public record AdminConfigField(String type, String editableBy, JsonNode defaultValue,
                                   JsonNode value, boolean overridden,
                                   java.util.List<PluginManifest.ConfigOption> options,
                                   JsonNode label, JsonNode description) {
    }

    /**
     * A request to change a plugin's storage limits; {@code null} clears that limit.
     *
     * @param quotaBytes   the total to grant, or null to fall back to the manifest/operator value
     * @param maxFileBytes the per-file limit to grant, or null for the same
     */
    public record BlobLimitRequest(Long quotaBytes, Long maxFileBytes) {
    }

    /**
     * A plugin's storage situation, as the admin form needs it (§11.1).
     *
     * <p>Both what is <em>in force</em> and where it came from: an admin raising a limit needs to see the
     * usage that prompted it, and needs to be able to tell "this is the plugin's own declaration" from "this
     * is a decision someone made here". {@code hardQuotaBytes} is what the operator will allow to be
     * granted, so the form can say so instead of silently clamping what was typed.
     *
     * @param usedBytes          what the plugin currently occupies
     * @param fileCount          how many files that is
     * @param quotaBytes         the total in force
     * @param maxFileBytes       the per-file limit in force
     * @param quotaOverridden    whether the total comes from an admin grant rather than manifest/default
     * @param maxFileOverridden  the same for the per-file limit
     * @param declaredQuotaBytes what the manifest asked for, or null
     * @param declaredMaxFileBytes the same for one file
     * @param hardQuotaBytes     the most an admin may grant here, or null for no bound
     * @param hardMaxFileBytes   the same for one file
     */
    public record AdminBlobs(long usedBytes, long fileCount, long quotaBytes, long maxFileBytes,
                             boolean quotaOverridden, boolean maxFileOverridden,
                             Long declaredQuotaBytes, Long declaredMaxFileBytes,
                             Long hardQuotaBytes, Long hardMaxFileBytes) {
    }

    /** How many documents a purge removed. */
    public record PurgeResult(int purged) {
    }
}
