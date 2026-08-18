// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.Optional;

/**
 * A single byte range from an HTTP {@code Range} header (RFC 9110 §14).
 *
 * <p>§11 asks for range support from day one because audio is the heavy future customer: a listener
 * scrubbing to the middle of a 100 MB recording sends a range, and a server that ignores it sends the whole
 * file to answer a seek.
 *
 * <p><strong>Only one range, deliberately.</strong> Multipart ranges require a {@code multipart/byteranges}
 * response, which is a wire format with its own boundary handling and is used by essentially nothing —
 * browsers and audio elements ask for one range. A request for several is treated as "no usable range" and
 * answered with the whole file, which is a valid response to any range request and the one that cannot be
 * subtly wrong.
 *
 * @param start        first byte, inclusive
 * @param endInclusive last byte, inclusive
 */
public record RangeHeader(long start, long endInclusive) {

    /**
     * Parses a {@code Range} header against a known total size.
     *
     * <p>Handles the three forms that appear in practice: {@code bytes=0-499}, {@code bytes=500-} (to the
     * end) and {@code bytes=-500} (the last 500 bytes). Anything else — multiple ranges, a non-{@code bytes}
     * unit, a start past the end, garbage — returns empty, and the caller sends 200 with the whole file.
     *
     * @param header the raw header value, or null when absent
     * @param total  the resource's total size in bytes
     * @return the resolved range, clamped to the resource, or empty when there is no usable single range
     */
    public static Optional<RangeHeader> parse(String header, long total) {
        if (header == null || total <= 0) {
            return Optional.empty();
        }
        String value = header.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.startsWith("bytes=")) {
            return Optional.empty();
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return Optional.empty();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        try {
            if (from.isEmpty()) {
                // Suffix form: the last N bytes. N of zero names nothing, which is not a range.
                long suffix = Long.parseLong(to);
                if (suffix <= 0) {
                    return Optional.empty();
                }
                long start = Math.max(0, total - suffix);
                return Optional.of(new RangeHeader(start, total - 1));
            }
            long start = Long.parseLong(from);
            if (start < 0 || start >= total) {
                return Optional.empty();
            }
            long end = to.isEmpty() ? total - 1 : Math.min(Long.parseLong(to), total - 1);
            if (end < start) {
                return Optional.empty();
            }
            return Optional.of(new RangeHeader(start, end));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** How many bytes this range covers. */
    public long length() {
        return endInclusive - start + 1;
    }

    /**
     * The {@code Content-Range} value for this range.
     *
     * @param total the resource's total size
     * @return e.g. {@code bytes 0-499/1234}
     */
    public String contentRange(long total) {
        return "bytes %d-%d/%d".formatted(start, endInclusive, total);
    }
}
