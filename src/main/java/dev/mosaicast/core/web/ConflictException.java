// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

/**
 * Signals a request that conflicts with the current state, rendered as HTTP 409
 * {@code application/problem+json} (ARCHITECTURE §13) — e.g. removing a user's last identity (§8.4).
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
