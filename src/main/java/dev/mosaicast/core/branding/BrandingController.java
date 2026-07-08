// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.web.NotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Serves branding assets and lets an admin upload/clear them (ARCHITECTURE §12.2). Serving is public with
 * an ETag from the asset's last-modified (so a change propagates immediately, otherwise it is cached) and
 * falls back to the bundled defaults. Upload/clear are ADMIN-only (via {@code /api/admin/**}).
 */
@RestController
public class BrandingController {

    private final BrandingService branding;

    public BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping("/branding/{key}")
    public ResponseEntity<byte[]> serve(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        BrandingService.Servable asset = branding.resolve(assetOf(key));
        if (asset.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(asset.etag()).build();
        }
        return ResponseEntity.ok()
                .eTag(asset.etag())
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
                .contentType(MediaType.parseMediaType(asset.mime()))
                // Raster/served-as-declared; nosniff (Spring Security default) blocks type confusion.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(asset.bytes());
    }

    @PostMapping("/api/admin/branding/{key}")
    public ResponseEntity<Void> upload(@PathVariable String key, @RequestParam("file") MultipartFile file) {
        branding.upload(assetOf(key), file);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/admin/branding/{key}")
    public ResponseEntity<Void> clear(@PathVariable String key) {
        branding.clear(assetOf(key));
        return ResponseEntity.noContent().build();
    }

    private static BrandingAsset assetOf(String key) {
        return BrandingAsset.fromKey(key)
                .orElseThrow(() -> new NotFoundException("Unknown branding asset: " + key));
    }
}
