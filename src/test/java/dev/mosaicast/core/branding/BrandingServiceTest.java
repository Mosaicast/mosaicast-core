// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStore;
import java.nio.charset.StandardCharsets;
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
        MockMultipartFile png = new MockMultipartFile("file", "logo.png", "image/png", pngBytes());
        UUID blobId = UUID.randomUUID();
        when(blobStore.put(eq("branding"), eq("logo"), any(), eq("image/png")))
                .thenReturn(new BlobRef(blobId, "branding"));

        service().upload(BrandingAsset.LOGO, png);

        verify(site).setAsset(BrandingAsset.LOGO, blobId);
    }

    @Test
    void refusesAFileThatIsOnlyLabelledAsRaster() {
        // The declared Content-Type is a header the client writes. This class promises "only raster is
        // stored" and used to check nothing else, so an SVG labelled image/png was accepted, stored, and
        // served back with that same attacker-chosen type — leaving containment to a nosniff header set in a
        // different file.
        MockMultipartFile svg = new MockMultipartFile("file", "logo.png", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service().upload(BrandingAsset.LOGO, svg))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(blobStore);
    }

    @Test
    void storesTheFormatItActuallyIsRatherThanTheOneClaimed() {
        // A real JPEG mislabelled image/png is still a JPEG, and serving it as PNG would be the host
        // repeating the client's claim back to the next browser.
        MockMultipartFile jpeg = new MockMultipartFile("file", "logo.png", "image/png",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0});
        when(blobStore.put(eq("branding"), eq("logo"), any(), eq("image/jpeg")))
                .thenReturn(new BlobRef(UUID.randomUUID(), "branding"));

        service().upload(BrandingAsset.LOGO, jpeg);

        verify(blobStore).put(eq("branding"), eq("logo"), any(), eq("image/jpeg"));
    }

    @Test
    void refusesAFileTooShortToIdentify() {
        MockMultipartFile stub = new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service().upload(BrandingAsset.LOGO, stub))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A minimal but genuine PNG signature plus enough bytes to be sniffable. */
    private static byte[] pngBytes() {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R',
        };
    }
}
