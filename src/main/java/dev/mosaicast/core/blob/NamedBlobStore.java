// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

/**
 * A {@link BlobStore} that can be named in configuration (ARCHITECTURE §11).
 *
 * <p>The name is how {@code mosaicast.blobs.namespaces} points a namespace at a backend, and it is the
 * backend's own to declare rather than the router's to assign: a bean the router has never heard of can
 * register itself simply by being on the context under a name, which is what makes "add a backend" a new
 * class and a config line instead of an edit to the routing code.
 *
 * <p>Deliberately separate from {@link BlobStore}: {@link BlobStoreRouter} is a {@code BlobStore} and must
 * <em>not</em> be registrable, or it could be routed to itself.
 */
public interface NamedBlobStore extends BlobStore {

    /**
     * The name this backend answers to in configuration, lower-case and stable.
     *
     * @return the backend name, e.g. {@code postgres}
     */
    String backendName();
}
