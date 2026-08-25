// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import dev.mosaicast.core.external.settings.EnvProbe;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsFieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every provider bean on the classpath, indexed by kind and id (ARCHITECTURE §12.7).
 *
 * <p><strong>A bad descriptor fails the boot.</strong> A broken <em>plugin</em> manifest is rejected and the
 * plugin skipped, because a plugin is third-party and the site has to survive it. A provider descriptor is
 * first-party code compiled into the host: a wrong one is a bug that should never reach a running instance,
 * and the cheapest place to find out is startup. The same argument {@code OutboundTargetPolicy} makes for
 * refusing to start on a malformed allow-list.
 */
@Component
public class ExternalServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceRegistry.class);

    private final Map<ExternalServiceKind, Map<String, ExternalProvider<?, ?>>> byKind =
            new LinkedHashMap<>();

    public ExternalServiceRegistry(List<ExternalProvider<?, ?>> providers) {
        for (ExternalProvider<?, ?> provider : providers) {
            ProviderDescriptor descriptor = provider.descriptor();
            validate(descriptor);
            Map<String, ExternalProvider<?, ?>> forKind =
                    byKind.computeIfAbsent(descriptor.kind(), kind -> new LinkedHashMap<>());
            ExternalProvider<?, ?> clash = forKind.put(descriptor.id(), provider);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two %s providers claim the id '%s'".formatted(descriptor.kind().id(), descriptor.id()));
            }
        }
        byKind.forEach((kind, forKind) ->
                log.info("External services: {} provider(s) available for {}", forKind.size(), kind.id()));
    }

    /** Every provider for a kind, in registration order. */
    public List<ExternalProvider<?, ?>> providers(ExternalServiceKind kind) {
        return List.copyOf(byKind.getOrDefault(kind, Map.of()).values());
    }

    /** Every descriptor for a kind — what the admin section lists. */
    public List<ProviderDescriptor> descriptors(ExternalServiceKind kind) {
        return providers(kind).stream().map(ExternalProvider::descriptor).toList();
    }

    /** One provider, if a bean answers to that id for that kind. */
    public Optional<ExternalProvider<?, ?>> provider(ExternalServiceKind kind, String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKind.getOrDefault(kind, Map.of()).get(providerId.trim()));
    }

    /** Whether a kind has any provider at all — an admin section with none says so rather than looking broken. */
    public boolean hasProviders(ExternalServiceKind kind) {
        return !byKind.getOrDefault(kind, Map.of()).isEmpty();
    }

    /**
     * Checks a descriptor the way a manifest is checked at load, and throws rather than skipping.
     *
     * @throws IllegalStateException on anything that would surface later as a broken admin page
     */
    static void validate(ProviderDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        if (descriptor.id() == null || !ProviderDescriptor.ID.matcher(descriptor.id()).matches()) {
            problems.add("id '%s' must be lower-case letters, digits and dashes".formatted(descriptor.id()));
        }
        if (descriptor.kind() == null) {
            problems.add("no kind");
        }
        if (descriptor.defaultRequestsPerMinute() < 1) {
            problems.add("defaultRequestsPerMinute must be at least 1");
        }
        if (descriptor.defaultTimeout() == null || descriptor.defaultTimeout().isNegative()
                || descriptor.defaultTimeout().isZero()) {
            problems.add("defaultTimeout must be positive");
        }

        List<String> seen = new ArrayList<>();
        for (SettingsField field : descriptor.settings().fields()) {
            String where = "field '%s'".formatted(field.key());
            if (!SettingsField.isLegalKey(field.key())) {
                problems.add("%s: key must be lowerCamelCase".formatted(where));
                continue;
            }
            if (!seen.add(field.key()) && seen.indexOf(field.key()) != seen.lastIndexOf(field.key())) {
                problems.add("%s: declared twice".formatted(where));
            }
            problems.addAll(validateField(field, where));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Provider '%s' is not usable: %s"
                    .formatted(descriptor.id(), String.join("; ", problems)));
        }
    }

    private static List<String> validateField(SettingsField field, String where) {
        List<String> problems = new ArrayList<>();
        if (field.type() == SettingsFieldType.SELECT) {
            if (field.options().isEmpty()) {
                problems.add("%s: a select needs options".formatted(where));
            } else if (field.defaultValue() != null && field.options().stream()
                    .noneMatch(option -> option.value().equals(field.defaultValue().stringValue()))) {
                problems.add("%s: the default is not one of its options".formatted(where));
            }
        }
        if (field.min() != null && field.max() != null && field.min() > field.max()) {
            problems.add("%s: min is above max".formatted(where));
        }
        if (field.defaultValue() != null
                && field.accepts(field.defaultValue()) instanceof SettingsField.Result.Rejected rejected) {
            problems.add("%s: its own default %s".formatted(where, rejected.reason()));
        }
        boolean needsSuffix = field.type() == SettingsFieldType.ENV_SECRET;
        if (needsSuffix && !EnvProbe.isLegalSuffix(field.envVarSuffix())) {
            // Not style: the admin API answers "is this variable set?", and a descriptor free to name any
            // variable turns that endpoint into an oracle over the whole process environment.
            problems.add("%s: envVarSuffix '%s' must be UPPER_SNAKE and at most 32 characters"
                    .formatted(where, field.envVarSuffix()));
        }
        if (!needsSuffix && field.envVarSuffix() != null && field.type() != SettingsFieldType.INFO) {
            problems.add("%s: only env-backed fields may name a variable".formatted(where));
        }
        return problems;
    }
}
