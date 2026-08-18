// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * An upload refused for size: either the file alone is over the plugin's per-file ceiling, or storing it
 * would put the plugin over its total (ARCHITECTURE §11).
 *
 * <p>Its own type so a plugin author can tell it from a type refusal without matching on English — the same
 * reason {@code BackendOwnedKeyException} is worded apart from a role refusal. Both answer 413, and the
 * problem {@code type} is what distinguishes them: the two have different fixes (send a smaller file, versus
 * delete something first) and a client that cannot tell which cannot say which.
 */
public class BlobQuotaExceededException extends RuntimeException {

    /**
     * @param message what was over which limit, in numbers the caller can act on
     */
    public BlobQuotaExceededException(String message) {
        super(message);
    }
}
