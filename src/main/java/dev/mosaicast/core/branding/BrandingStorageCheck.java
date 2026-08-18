// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.blob.BlobStoreRouter;
import dev.mosaicast.core.blob.PostgresBlobStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the {@code branding} namespace is routed away from Postgres (ARCHITECTURE §11).
 *
 * <p><strong>Branding blobs cannot live anywhere else, and the reason is a foreign key.</strong>
 * {@code site_config.logo_asset_id}, {@code favicon_asset_id} and {@code dark_logo_asset_id} are
 * {@code UUID REFERENCES blob(id)} — the pointer to a branding asset is a row id in the Postgres
 * {@code blob} table, not a namespaced key. A backend that stores the bytes elsewhere creates no such row,
 * so the pointer has nothing to reference and the upload fails on the constraint.
 *
 * <p>Which is a fine failure, except for <em>when</em> it happens: without this check, the symptom is an
 * FK violation the first time an admin uploads a logo, from a configuration line that reads as perfectly
 * reasonable, possibly long after it was written. Startup is the honest place to say so — the same call
 * {@link BlobStoreRouter} makes about a rule naming a backend that does not exist.
 *
 * <p>This is a constraint of branding's data model rather than of blob storage, which is why the check
 * lives here rather than in the router: the router knows the routing, and the component with the
 * requirement is the one that should assert it. Making branding portable means giving it a key-based
 * lookup and dropping the foreign key — at which point this class goes away, and its absence will be a
 * deliberate deletion rather than a gap nobody noticed.
 */
@Component
public class BrandingStorageCheck {

    private final BlobStoreRouter router;

    public BrandingStorageCheck(BlobStoreRouter router) {
        this.router = router;
    }

    @PostConstruct
    void verifyBrandingStaysInPostgres() {
        String backend = router.backendNameFor(BrandingService.NAMESPACE);
        if (!PostgresBlobStore.NAME.equals(backend)) {
            throw new IllegalStateException(
                    ("The '%s' blob namespace is routed to backend '%s', but branding assets are referenced "
                            + "by site_config.*_asset_id as a foreign key into the Postgres 'blob' table, so "
                            + "they must be stored there. Remove the mosaicast.blobs.namespaces entry for "
                            + "'%s' (or point it at '%s').")
                            .formatted(BrandingService.NAMESPACE, backend, BrandingService.NAMESPACE,
                                    PostgresBlobStore.NAME));
        }
    }
}
