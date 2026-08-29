// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import dev.mosaicast.core.external.error.NoProviderConfiguredException;
import dev.mosaicast.core.external.error.ProviderMisconfiguredException;
import dev.mosaicast.core.external.pipeline.ExternalCallPipeline;
import dev.mosaicast.core.external.settings.EnvProbe;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsFieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves "which provider, configured how" for a kind (ARCHITECTURE §12.7).
 *
 * <p>The seam every consumer goes through. In this release it resolves and reports readiness; the call
 * pipeline — cache, bulkhead, rate limit — wraps it later without any consumer changing.
 */
@Service
public class ExternalServices {

    private final ExternalServiceRegistry registry;
    private final ExternalServiceSettingsService settings;
    private final EnvProbe env;
    private final ExternalCallPipeline pipeline;

    public ExternalServices(ExternalServiceRegistry registry, ExternalServiceSettingsService settings,
                            EnvProbe env, ExternalCallPipeline pipeline) {
        this.registry = registry;
        this.settings = settings;
        this.env = env;
        this.pipeline = pipeline;
    }

    /** A selected provider with its settings resolved. */
    public record Resolved(ProviderDescriptor descriptor, ExternalProvider<?, ?> provider,
                           ProviderConfig config) {
    }

    /**
     * The selected provider for a kind, settings resolved and <strong>wrapped</strong>, or empty when none
     * is selected.
     *
     * <p>Callers get the pipeline's view — cache, rate limit, bulkhead — never the raw bean. That is what
     * makes those guarantees a property of the system rather than of each provider remembering.
     */
    public Optional<Resolved> resolve(ExternalServiceKind kind) {
        return settings.selected(kind)
                .flatMap(providerId -> registry.provider(kind, providerId))
                .map(provider -> {
                    ProviderConfig config = configFor(kind, provider.descriptor());
                    return new Resolved(provider.descriptor(), wrap(kind, provider, config), config);
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ExternalProvider<?, ?> wrap(ExternalServiceKind kind, ExternalProvider<?, ?> provider,
                                        ProviderConfig config) {
        return pipeline.wrap(kind, (ExternalProvider) provider, config);
    }

    /**
     * The same, but refusing rather than returning empty.
     *
     * @throws NoProviderConfiguredException  when the admin selected none
     * @throws ProviderMisconfiguredException when one is selected but cannot run as configured
     */
    public Resolved require(ExternalServiceKind kind) {
        Resolved resolved = resolve(kind).orElseThrow(() -> new NoProviderConfiguredException(
                "No %s provider is configured for this site".formatted(kind.id())));
        List<String> missing = missingSettings(kind, resolved.descriptor());
        if (!missing.isEmpty()) {
            // Names the fields, never their values — this is the path a credential would leak down.
            throw new ProviderMisconfiguredException("The %s provider '%s' still needs: %s"
                    .formatted(kind.id(), resolved.descriptor().id(), String.join(", ", missing)));
        }
        return resolved;
    }

    /** Whether a call would even be attempted. What a UI disables its buttons on. */
    public boolean ready(ExternalServiceKind kind) {
        return resolve(kind)
                .map(resolved -> missingSettings(kind, resolved.descriptor()).isEmpty())
                .orElse(false);
    }

    /**
     * Which required settings are still unsatisfied, in declaration order.
     *
     * <p>Required is the provider's own claim, and an <em>optional</em> credential is a real thing: a
     * self-hosted LibreTranslate with {@code keyRequired: false} needs no token, while the same image behind
     * a public URL enforces one. A provider that marked its key mandatory would be unusable on the first;
     * one that omitted the field entirely would be unusable on the second.
     */
    public List<String> missingSettings(ExternalServiceKind kind, ProviderDescriptor descriptor) {
        List<String> missing = new ArrayList<>();
        for (SettingsField field : descriptor.settings().fields()) {
            if (!field.required() || field.type() == SettingsFieldType.INFO) {
                continue;
            }
            if (!isSatisfied(kind, descriptor, field)) {
                missing.add(field.key());
            }
        }
        return List.copyOf(missing);
    }

    /** Whether one field has a usable value, wherever that field said its value lives. */
    public boolean isSatisfied(ExternalServiceKind kind, ProviderDescriptor descriptor, SettingsField field) {
        if (field.type() == SettingsFieldType.ENV_SECRET) {
            return env.isSet(kind, descriptor.id(), field.envVarSuffix());
        }
        var override = settings.overrides(kind, descriptor.id()).get(field.key());
        if (override != null && !override.isNull()) {
            return !(override.isString() && override.stringValue().isBlank());
        }
        return field.defaultValue() != null;
    }

    private ProviderConfig configFor(ExternalServiceKind kind, ProviderDescriptor descriptor) {
        return new ProviderConfigImpl(kind, descriptor.id(), descriptor.settings(),
                settings.overrides(kind, descriptor.id()), settings, env);
    }
}
