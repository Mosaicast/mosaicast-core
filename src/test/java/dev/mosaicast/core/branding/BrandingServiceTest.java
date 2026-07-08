// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Risk-point tests for branding upload security (ARCHITECTURE §12.2, §13.5): SVG uploads are rejected (XSS
 * vector), oversized uploads are rejected, and a valid raster is stored and linked.
 */
@ExtendWith(MockitoExtension.class)
class BrandingServiceTest {

    @Mock
    private BlobStore blobStore;

    @Mock
    private SiteConfigService site;

    private BrandingService service() {
        return new BrandingService(blobStore, site);
    }

    @Test
    void rejectsSvgUpload() {
        MockMultipartFile svg = new MockMultipartFile(
                "file", "logo.svg", "image/svg+xml", "<svg onload=\"alert(1)\"/>".getBytes());

        assertThatThrownBy(() -> service().upload(BrandingAsset.LOGO, svg))
                .isInstanceOf(IllegalArgumentException.class);
        verify(blobStore, never()).put(any(), any(), any(), any());
    }

    @Test
    void rejectsOversizedUpload() {
        byte[] big = new byte[3 * 1024 * 1024]; // 3 MB > 2 MB cap
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", big);

        assertThatThrownBy(() -> service().upload(BrandingAsset.LOGO, file))
                .isInstanceOf(IllegalArgumentException.class);
        verify(blobStore, never()).put(any(), any(), any(), any());
    }

    @Test
    void storesValidRasterAndLinksIt() {
        MockMultipartFile png = new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3});
        UUID blobId = UUID.randomUUID();
        when(blobStore.put(eq("branding"), eq("logo"), any(), eq("image/png")))
                .thenReturn(new BlobRef(blobId, "branding"));

        service().upload(BrandingAsset.LOGO, png);

        verify(site).setAsset(BrandingAsset.LOGO, blobId);
    }
}
