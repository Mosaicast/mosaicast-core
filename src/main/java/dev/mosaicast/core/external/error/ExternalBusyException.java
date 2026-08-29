// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * The host is at its concurrency limit for this kind — 503 with a {@code Retry-After}.
 *
 * <p>Deliberately not a 429: the caller is not over any limit, the server is saturated. Preserving that
 * distinction is the same instinct that makes {@code PluginExtensions} mark a timed-out section rather than
 * dropping it — "found nothing" and "did not answer" are different, and so are "you asked too often" and
 * "we are full".
 */
public class ExternalBusyException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    public ExternalBusyException(String message) {
        super(message);
    }

    @Override
    public String problemType() {
        return "external-busy";
    }
}
