// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.io.InputStream;
import java.time.Instant;

/**
 * Streamable blob content (ARCHITECTURE §11 — "streaming, not all bytes"). {@link #stream} carries the
 * requested bytes (the whole blob, or a range); {@link #totalSize} is always the full object size, so a
 * range response can set {@code Content-Range}. The caller is responsible for closing the stream.
 *
 * @param mime      content type
 * @param totalSize the full blob size (not the length of this stream when ranged)
 * @param updatedAt last modification
 * @param stream    the bytes to write out; caller closes
 */
public record BlobContent(String mime, long totalSize, Instant updatedAt, InputStream stream) {
}
