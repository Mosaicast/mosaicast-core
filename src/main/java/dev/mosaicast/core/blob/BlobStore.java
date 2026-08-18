// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.io.InputStream;
import java.util.List;
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

    // ---- namespace-scoped operations (ARCHITECTURE §11) ----
    //
    // A namespace is the unit of ownership on this interface: `branding` is one, `plugin/<id>` is one per
    // plugin. Every backend answers these for its own namespaces, because the alternative — a shared index
    // in Postgres that every backend writes to — makes the index and the bytes two things that can disagree,
    // and nothing can then say which is right.
    //
    // The cost of self-containment is that each backend has to answer them *well*. That is deliberately not
    // this interface's problem: Postgres sums a column, a filesystem walks a directory, and an object store
    // will keep a counter rather than paginate its own prefix on every upload. What matters here is that
    // asking is possible at all, which is what the doc surface reaching into `BlobRepository` used to
    // prevent.

    /**
     * The blobs in a namespace, newest first, without their bytes.
     *
     * <p>Offset paging, matching the HTTP surface. That is a real constraint on a future object-store
     * backend, which pages by continuation token and cannot seek to an arbitrary offset cheaply — a media
     * library is browsed from the first page, so walking to a deep one is a cost that backend may cap. If
     * that stops being an acceptable answer, the fix is cursor paging on the plugin API, which is an SDK
     * contract change and is why it is not being made speculatively now.
     *
     * @param namespace the namespace to list
     * @param page      zero-based page index
     * @param size      page size
     * @return the page's metadata, newest first; empty past the end
     */
    List<BlobMetadata> list(String namespace, int page, int size);

    /**
     * How many blobs a namespace holds.
     *
     * @param namespace the namespace to count
     * @return the count
     */
    long count(String namespace);

    /**
     * What a namespace's blobs occupy in total — what a quota is checked against.
     *
     * <p>Called on <strong>every upload</strong>, so a backend owes it an answer that is cheap at that
     * frequency. Postgres sums an indexed column; an object store should maintain a counter and recompute it
     * on demand rather than list its own prefix each time.
     *
     * @param namespace the namespace to total
     * @return the total size in bytes, zero for an empty or unknown namespace
     */
    long usedBytes(String namespace);

    /**
     * Removes every blob in a namespace — what "purge plugin data" means for files (§7.8).
     *
     * @param namespace the namespace to empty
     * @return how many blobs were removed
     */
    int deleteNamespace(String namespace);

    /**
     * A URL that serves these bytes <em>without going through the app</em> — a CDN URL, or a presigned one
     * (ARCHITECTURE §11) — or empty when this backend has none.
     *
     * <p><strong>Empty is the normal answer, and callers must handle it</strong> by serving the bytes
     * themselves. That is what Postgres does and always will: there is no URL that reaches into a BYTEA
     * column, and the previous version of this method returned {@code "/api/blobs/" + id} for a route
     * nothing has ever mapped — a contract that read as satisfied and answered with a 404.
     *
     * <p>This is the seam that makes an object store worth having. Proxying every byte through the app
     * gives up most of the reason to move them out of the database, so a backend that can hand out a URL
     * says so here, and {@link BlobCapabilities#supportsPresignedUrls()} advertises it in advance.
     *
     * <p>{@code ctx} is what a presigned URL is signed <em>for</em>: tier-gated audio (§10, v2) may only be
     * handed a working URL after the entitlement check, so the decision cannot be cached per blob.
     *
     * @param ref the blob
     * @param ctx who is asking
     * @return a URL the caller may redirect to, or empty to serve the bytes through the app
     */
    Optional<String> directUrl(BlobRef ref, AccessContext ctx);

    /** What this backend can do — queried instead of the concrete type. */
    BlobCapabilities capabilities();
}
