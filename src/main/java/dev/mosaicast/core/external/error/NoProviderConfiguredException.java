// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * No provider is selected for this kind — 409.
 *
 * <p>Thrown rather than returned as an empty {@code Optional}. A service facade offers an {@code available()}
 * check so a UI can disable its button; reaching the call anyway is a UI-state bug and deserves to be loud.
 * Returning empty would also conflate "nobody configured this" with "the provider returned nothing", which
 * are different answers to different questions.
 */
public class NoProviderConfiguredException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    public NoProviderConfiguredException(String message) {
        super(message);
    }

    @Override
    public String problemType() {
        return "external-no-provider";
    }
}
