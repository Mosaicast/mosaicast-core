// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

/**
 * Signals a missing resource, rendered as a real HTTP 404 {@code application/problem+json}
 * (ARCHITECTURE §13, §6.6 "real HTTP 404s ... no soft-404"). Message text stays English; the UI
 * translates.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
