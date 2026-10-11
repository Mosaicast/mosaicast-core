// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

/**
 * A first login while the site is not open and sign-ups are not allowed (ARCHITECTURE §18.3). The login
 * fails with {@code login_error=signups_closed} rather than creating an account.
 */
public class SignupsClosedException extends RuntimeException {

    public SignupsClosedException() {
        super("New accounts can't be created until the site opens.");
    }
}
