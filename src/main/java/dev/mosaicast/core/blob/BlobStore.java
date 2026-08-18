// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.io.InputStream;
import java.util.Optional;

/**
 * The one storage interface in core (ARCHITECTURE §11). Branding is its first customer (Postgres behind
 * it), audio the heavy future customer — through the same door. Streaming-first with range support from
 * day one; namespace routing lets {@code branding/*} stay in Postgres forever while {@code audio/*} moves
 * to S3/CDN later, with one backend per namespace.
 */
public interface BlobStore {

    /**
     * Stores (or replaces) the blob at {@code (namespace, key)} and returns its handle. The stream is
     * fully read and closed by the store.
     */
    BlobRef put(String namespace, String key, InputStream data, String mime);

    /**
     * Stores a blob along with who uploaded it and under what name (§11).
     *
     * <p>Attribution is optional to <em>store</em> and optional to <em>support</em>: it is a display and
     * housekeeping detail, not part of addressing a blob, so a backend that cannot carry it (a future
     * object-store one, say) keeps working through this default. Branding never supplies it — there is one
     * logo and an admin uploaded it — and a plugin's media library always does.
     *
     * @param namespace the namespace
     * @param key       the key within it
     * @param data      the content; fully read and closed by the store
     * @param mime      the content type
     * @param filename  the original filename for display, or {@code null}; never treated as a path
     * @param uploader  the uploading user, or {@code null} when there is no caller (a backend task)
     * @return the stored blob's handle
     */
    default BlobRef put(String namespace, String key, InputStream data, String mime, String filename,
                        java.util.UUID uploader) {
        return put(namespace, key, data, mime);
    }

    /** Metadata for a namespaced key, without the bytes (for ETag/existence checks). */
    Optional<BlobMetadata> stat(String namespace, String key);

    /** Metadata for a handle, without the bytes. */
    Optional<BlobMetadata> stat(BlobRef ref);

    /** Opens the full content for streaming. */
    BlobContent get(BlobRef ref);

    /**
     * Opens a byte range {@code [start, endInclusive]} for streaming (HTTP Range / audio seeking). The
     * backend reads only the requested slice where possible.
     */
    BlobContent getRange(BlobRef ref, long start, long endInclusive);

    /** Removes the blob. */
    void delete(BlobRef ref);

    /**
     * A URL for the blob appropriate to the caller: a direct/presigned URL where the backend supports it,
     * otherwise an own-endpoint path the app serves (ARCHITECTURE §11).
     */
    String urlFor(BlobRef ref, AccessContext ctx);

    /** What this backend can do — queried instead of the concrete type. */
    BlobCapabilities capabilities();
}
