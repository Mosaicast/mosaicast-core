// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth configuration (ARCHITECTURE §8.5). The bootstrap identity — a {@code (provider, external_id)}
 * pair set via the environment on first start — is granted {@code ADMIN} when it logs in, so an operator
 * can promote others afterwards.
 *
 * @param bootstrapProvider   the provider of the bootstrap admin identity (e.g. {@code discord}), or blank
 * @param bootstrapExternalId the external id of the bootstrap admin at that provider, or blank
 */
@ConfigurationProperties(prefix = "mosaicast.auth")
public record AuthProperties(String bootstrapProvider, String bootstrapExternalId) {

    /** True when the given identity is the configured bootstrap admin (§8.5). */
    public boolean isBootstrapAdmin(String provider, String externalId) {
        return bootstrapProvider != null && !bootstrapProvider.isBlank()
                && bootstrapExternalId != null && !bootstrapExternalId.isBlank()
                && bootstrapProvider.equalsIgnoreCase(provider)
                && bootstrapExternalId.equals(externalId);
    }
}
