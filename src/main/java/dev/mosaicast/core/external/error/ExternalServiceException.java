// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

/**
 * Something stopped an external-service call (ARCHITECTURE §12.7).
 *
 * <p>Unchecked, and subclassed rather than carrying an enum, because each subclass maps to its own HTTP
 * status and its own stable problem {@code type}. A caller that cannot tell "you asked too often" from "we
 * are full" from "nobody configured this" cannot act on any of them, and matching on English is not an
 * option — the same reasoning {@code ApiExceptionHandler} already applies to its two different 403s.
 */
public abstract class ExternalServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected ExternalServiceException(String message) {
        super(message);
    }

    protected ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The stable problem {@code type} slug, appended to {@code https://mosaicast.dev/problems/}. */
    public abstract String problemType();
}
