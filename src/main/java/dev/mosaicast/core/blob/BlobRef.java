// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.util.UUID;

/**
 * A handle to a stored blob (ARCHITECTURE §11): its id plus the namespace that routes it to a backend.
 *
 * @param id        the blob id
 * @param namespace the storage namespace (e.g. {@code branding}, {@code audio})
 */
public record BlobRef(UUID id, String namespace) {
}
