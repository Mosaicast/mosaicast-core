// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.blob.BlobStoreProperties;
import dev.mosaicast.core.blob.BlobStoreRouter;
import dev.mosaicast.core.blob.InMemoryBlobStore;
import dev.mosaicast.core.blob.NamedBlobStore;
import dev.mosaicast.core.blob.PostgresBlobStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The startup guard on where branding assets may live (ARCHITECTURE §11).
 *
 * <p>The failure it prevents is not the FK violation — that would happen anyway — but <em>when</em> it
 * happens: without this, a reasonable-looking config line breaks the next logo upload, possibly weeks after
 * it was written, with an error naming a constraint rather than the setting that caused it.
 */
class BrandingStorageCheckTest {

    private static NamedBlobStore postgres() {
        // Only the name matters here; the check asks the router where a namespace resolves, nothing more.
        PostgresBlobStore postgres = Mockito.mock(PostgresBlobStore.class);
        Mockito.when(postgres.backendName()).thenReturn(PostgresBlobStore.NAME);
        return postgres;
    }

    private static BrandingStorageCheck checkWith(Map<String, String> namespaces) {
        BlobStoreRouter router = new BlobStoreRouter(
                List.of(postgres(), new InMemoryBlobStore()),
                new BlobStoreProperties(PostgresBlobStore.NAME, namespaces));
        return new BrandingStorageCheck(router);
    }

    @Test
    void passesWhenBrandingIsWhereItHasToBe() {
        assertThatCode(() -> checkWith(Map.of()).verifyBrandingStaysInPostgres())
                .doesNotThrowAnyException();
        // Naming it explicitly is the same answer as not naming it at all.
        assertThatCode(() -> checkWith(Map.of("branding", PostgresBlobStore.NAME))
                .verifyBrandingStaysInPostgres()).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWhenBrandingIsRoutedElsewhere() {
        assertThatThrownBy(() -> checkWith(Map.of("branding", InMemoryBlobStore.NAME))
                .verifyBrandingStaysInPostgres())
                .isInstanceOf(IllegalStateException.class)
                // The message has to name the setting, not just the constraint: an operator reading it needs
                // to know which line to delete.
                .hasMessageContaining("mosaicast.blobs.namespaces")
                .hasMessageContaining("branding");
    }

    @Test
    void refusesWhenBrandingIsCaughtByADefaultPointedElsewhere() {
        // The subtler case: nobody named `branding`, but the default backend moved, so it goes along too.
        BlobStoreRouter router = new BlobStoreRouter(
                List.of(postgres(), new InMemoryBlobStore()),
                new BlobStoreProperties(InMemoryBlobStore.NAME, Map.of()));

        assertThatThrownBy(() -> new BrandingStorageCheck(router).verifyBrandingStaysInPostgres())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void leavesEveryOtherNamespaceAlone() {
        // The guard is about branding's foreign key, not about blob storage in general — routing plugins
        // away has to keep working, since that is the point of the seam.
        BlobStoreRouter router = new BlobStoreRouter(
                List.of(postgres(), new InMemoryBlobStore()),
                new BlobStoreProperties(PostgresBlobStore.NAME, Map.of("plugin", InMemoryBlobStore.NAME)));

        assertThatCode(() -> new BrandingStorageCheck(router).verifyBrandingStaysInPostgres())
                .doesNotThrowAnyException();
        assertThat(router.backendNameFor("plugin/wiki")).isEqualTo(InMemoryBlobStore.NAME);
    }
}
