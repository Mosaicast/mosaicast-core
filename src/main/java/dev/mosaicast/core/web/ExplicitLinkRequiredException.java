// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

/**
 * Signals that an anonymous login could correspond to an existing account (a verified-email match) but
 * must not be merged silently (ARCHITECTURE §8.3, the conservative variant). The user should log in with
 * their existing method and link the new provider in settings. Rendered as HTTP 409
 * {@code application/problem+json}.
 */
public class ExplicitLinkRequiredException extends RuntimeException {

    public ExplicitLinkRequiredException(String message) {
        super(message);
    }
}
