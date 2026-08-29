// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * A provider is selected but a setting it needs is missing — 409.
 *
 * <p>The message names the field and never its value: for a credential that would put the secret in an error
 * body, an access log and the admin log viewer in one step.
 */
public class ProviderMisconfiguredException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    public ProviderMisconfiguredException(String message) {
        super(message);
    }

    @Override
    public String problemType() {
        return "external-provider-misconfigured";
    }
}
