// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * The provider answered with a failure, or with something unusable — 502.
 *
 * <p><strong>The message never carries the upstream response body.</strong> An error body from a translation
 * API can echo the request, credential included; and for a self-hosted endpoint on a private address, a body
 * returned to the caller is a read-back oracle for whatever is actually listening there. Providers construct
 * these with a sanitized message and log the detail — the same rule {@code OutboundTargetPolicy} follows when
 * it answers every refusal with one identical string.
 */
public class ExternalProviderException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    public ExternalProviderException(String message) {
        super(message);
    }

    public ExternalProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String problemType() {
        return "external-provider-failed";
    }
}
