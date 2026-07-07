// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

/**
 * Thrown when a {@link FeedSource} cannot fetch (network error, bad status, unparseable body). The
 * scheduler records the failure and backs off; the last successful state stays visible (ARCHITECTURE §5.4).
 */
public class FetchException extends Exception {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
