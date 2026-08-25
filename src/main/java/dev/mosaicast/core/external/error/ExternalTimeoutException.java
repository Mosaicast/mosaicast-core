// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * The provider did not finish in its budget — 504.
 *
 * <p>Separate from {@link ExternalBusyException} on purpose: waiting for a permit and waiting for a remote
 * host are different failures with different fixes, and one status covering both would tell an operator
 * nothing about which knob to turn.
 */
public class ExternalTimeoutException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    public ExternalTimeoutException(String message) {
        super(message);
    }

    public ExternalTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String problemType() {
        return "external-timeout";
    }
}
