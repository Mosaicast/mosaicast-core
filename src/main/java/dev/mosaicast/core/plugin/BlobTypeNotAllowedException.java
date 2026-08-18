// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * An upload refused for its content type (ARCHITECTURE §11, §12.2) — either the declared type is not one
 * this plugin may store, or the bytes are not the type they claimed.
 *
 * <p>Both cases share one exception and one status (415) on purpose. Distinguishing them in the response
 * would tell a caller probing the sniffer whether their header or their payload gave them away, and the fix
 * is the same either way: upload the format you said you were uploading.
 */
public class BlobTypeNotAllowedException extends RuntimeException {

    /**
     * @param message which type was refused, without naming what the bytes turned out to be
     */
    public BlobTypeNotAllowedException(String message) {
        super(message);
    }
}
