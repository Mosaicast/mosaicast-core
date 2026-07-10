// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

/**
 * What a {@link BlobStore} backend can do (ARCHITECTURE §11). Callers query these rather than the concrete
 * backend, so swapping Postgres for S3/CDN per namespace is transparent.
 *
 * @param supportsRange         serves byte ranges (HTTP Range / audio seeking)
 * @param supportsPresignedUrls can hand out a direct/presigned URL instead of proxying bytes (S3 later)
 */
public record BlobCapabilities(boolean supportsRange, boolean supportsPresignedUrls) {
}
