// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.io.IOException;

/** Raised when a remote body passes the size cap {@link LimitedBodyHandler} enforces. */
public class BodyTooLargeException extends IOException {

    public BodyTooLargeException(String message) {
        super(message);
    }
}
