// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.time.Instant;

/**
 * Blob metadata without the bytes (ARCHITECTURE §11) — enough to decide an ETag, existence, and content
 * type before deciding whether (and what range) to stream.
 *
 * @param ref       the blob handle
 * @param key       the key within its namespace
 * @param mime      the content type
 * @param size      total size in bytes
 * @param updatedAt last modification (drives the ETag / Last-Modified, §12.2)
 * @param filename  the original filename for display, or {@code null} — branding assets have none, and a
 *                  plugin's media library is a list a human reads
 */
public record BlobMetadata(BlobRef ref, String key, String mime, long size, Instant updatedAt,
                           String filename) {

    /** Metadata for a blob nobody named — branding's shape, and what the store returned before §11 grew. */
    public BlobMetadata(BlobRef ref, String key, String mime, long size, Instant updatedAt) {
        this(ref, key, mime, size, updatedAt, null);
    }
}
