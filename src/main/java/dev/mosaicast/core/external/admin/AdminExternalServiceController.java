// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.admin;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ExternalServiceRegistry;
import dev.mosaicast.core.external.ExternalServiceSettingsService;
import dev.mosaicast.core.external.ExternalServices;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.admin.AdminExternalViews.AdminKindSection;
import dev.mosaicast.core.external.admin.AdminExternalViews.AdminProbeResult;
import dev.mosaicast.core.external.admin.AdminExternalViews.AdminProvider;
import dev.mosaicast.core.external.admin.AdminExternalViews.AdminSettingsField;
import dev.mosaicast.core.external.admin.AdminExternalViews.FieldError;
import dev.mosaicast.core.external.admin.AdminExternalViews.SelectProviderRequest;
import dev.mosaicast.core.external.cache.ExternalCacheStore;
import dev.mosaicast.core.external.settings.EnvProbe;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsFieldType;
import dev.mosaicast.core.external.settings.SettingsManifest;
import dev.mosaicast.core.web.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The external-services admin API (ARCHITECTURE §12.7).
 *
 * <p>Everything lives under {@code /api/admin/**}, so {@code SecurityConfig}'s catch-all gates it to ADMIN
 * and no line is added there. That is the right outcome: the only endpoints needing an entry of their own
 * are the ones that <em>widen</em> access, as plugin config does for podcasters, and nothing here should.
 */
@RestController
public class AdminExternalServiceController {

    private final ExternalServiceRegistry registry;
    private final ExternalServiceSettingsService settings;
    private final ExternalServices services;
    private final EnvProbe env;
    private final ExternalCacheStore cache;

    public AdminExternalServiceController(ExternalServiceRegistry registry,
                                          ExternalServiceSettingsService settings,
                                          ExternalServices services, EnvProbe env,
                                          ExternalCacheStore cache) {
        this.registry = registry;
        this.settings = settings;
        this.services = services;
        this.env = env;
        this.cache = cache;
    }

    @GetMapping("/api/admin/external")
    public List<AdminKindSection> sections() {
        return java.util.Arrays.stream(ExternalServiceKind.values()).map(this::section).toList();
    }

    @GetMapping("/api/admin/external/{kind}")
    public AdminKindSection section(@PathVariable String kind) {
        return section(parseKind(kind));
    }

    /** Selects a provider for a kind, or {@code null} for none. */
    @PutMapping("/api/admin/external/{kind}/provider")
    public AdminKindSection select(@PathVariable String kind, @RequestBody SelectProviderRequest request) {
        ExternalServiceKind parsed = parseKind(kind);
        String providerId = request.providerId();
        if (providerId != null && !providerId.isBlank()
                && registry.provider(parsed, providerId).isEmpty()) {
            throw new IllegalArgumentException("No %s provider '%s' is installed".formatted(kind, providerId));
        }
        settings.select(parsed, providerId);
        return section(parsed);
    }

    /**
     * Writes a batch of settings.
     *
     * <p><strong>All or nothing.</strong> Everything is validated before anything is stored — the rule
     * {@code AdminPluginController} applies to plugin config, for the same reason: a half-applied form leaves
     * a provider in a state the admin did not ask for and cannot see. Unlike that one, every bad field is
     * reported rather than only the first, so the form annotates every row in one round trip.
     */
    @PutMapping("/api/admin/external/{kind}/providers/{providerId}/settings")
    public AdminKindSection putSettings(@PathVariable String kind, @PathVariable String providerId,
                                        @RequestBody Map<String, JsonNode> values) {
        ExternalServiceKind parsed = parseKind(kind);
        ProviderDescriptor descriptor = descriptor(parsed, providerId);
        SettingsManifest manifest = descriptor.settings();

        List<FieldError> problems = manifest.validate(values).stream()
                .map(problem -> new FieldError(problem.key(), problem.reason()))
                .toList();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Rejected: " + problems.stream()
                    .map(problem -> "'%s' %s".formatted(problem.key(), problem.reason()))
                    .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        values.forEach((key, value) ->
                manifest.field(key).ifPresent(field -> settings.put(parsed, providerId, field, value)));
        return section(parsed);
    }

    /**
     * The "Test" button: one cheap round trip to the configured provider.
     *
     * <p>Bypasses nothing that protects the operator and everything that would make the answer stale — it
     * asks the provider directly rather than reporting what a cache remembers.
     */
    @PostMapping("/api/admin/external/{kind}/test")
    public AdminProbeResult test(@PathVariable String kind) {
        ExternalServiceKind parsed = parseKind(kind);
        ExternalServices.Resolved resolved = services.require(parsed);
        long started = System.nanoTime();
        ExternalProvider.ProbeResult result = resolved.provider().probe(resolved.config());
        long millis = (System.nanoTime() - started) / 1_000_000;
        return new AdminProbeResult(result.ok(), result.detail(), millis);
    }

    /** How much this kind's cache is holding, and what it has saved. */
    @GetMapping("/api/admin/external/{kind}/cache")
    public ExternalCacheStore.Stats cacheStats(@PathVariable String kind) {
        return cache.stats(parseKind(kind));
    }

    /**
     * Drops this kind's cached results, optionally for one provider only.
     *
     * <p>Manual because it is rarely right: a changed setting already makes old entries unreachable through
     * the fingerprint, so the button is for the case an operator knows the upstream itself changed under a
     * URL that did not.
     */
    @DeleteMapping("/api/admin/external/{kind}/cache")
    public java.util.Map<String, Long> purgeCache(@PathVariable String kind,
                                                  @RequestParam(required = false) String providerId) {
        return java.util.Map.of("removed", cache.purge(parseKind(kind), providerId));
    }

    // ---- mapping ----

    private AdminKindSection section(ExternalServiceKind kind) {
        String selected = settings.selected(kind).orElse(null);
        List<String> missing = selected == null
                ? List.of()
                : registry.provider(kind, selected)
                        .map(provider -> services.missingSettings(kind, provider.descriptor()))
                        .orElse(List.of());
        return new AdminKindSection(kind.id(), selected, services.ready(kind), missing,
                settings.encryptingSecrets(),
                registry.descriptors(kind).stream().map(descriptor -> provider(kind, descriptor)).toList());
    }

    private AdminProvider provider(ExternalServiceKind kind, ProviderDescriptor descriptor) {
        Map<String, JsonNode> overrides = settings.overrides(kind, descriptor.id());
        List<AdminSettingsField> fields = new ArrayList<>();
        for (SettingsField field : descriptor.settings().fields()) {
            boolean secret = field.type().isSecret();
            JsonNode override = overrides.get(field.key());
            fields.add(new AdminSettingsField(
                    field.key(),
                    field.type().name(),
                    field.label(),
                    field.description(),
                    secret ? null : field.defaultValue(),
                    // Null for a credential by construction: there is no branch here that could read one.
                    secret ? null : override,
                    override != null,
                    field.required(),
                    secret ? services.isSatisfied(kind, descriptor, field) : override != null,
                    field.min(),
                    field.max(),
                    field.options(),
                    field.type() == SettingsFieldType.ENV_SECRET || field.envVarSuffix() != null
                            ? env.varName(kind, descriptor.id(), field.envVarSuffix())
                            : null,
                    field.placeholder()));
        }
        return new AdminProvider(descriptor.id(), descriptor.name(), descriptor.description(),
                descriptor.homepage(), descriptor.privacyUrl(), descriptor.selfHosted(), descriptor.paid(),
                descriptor.thirdCountryTransfer(), List.copyOf(fields));
    }

    private static ExternalServiceKind parseKind(String kind) {
        return ExternalServiceKind.byId(kind)
                .orElseThrow(() -> new NotFoundException("No external service kind '%s'".formatted(kind)));
    }

    private ProviderDescriptor descriptor(ExternalServiceKind kind, String providerId) {
        return registry.provider(kind, providerId)
                .map(ExternalProvider::descriptor)
                .orElseThrow(() -> new NotFoundException(
                        "No %s provider '%s' is installed".formatted(kind.id(), providerId)));
    }
}
