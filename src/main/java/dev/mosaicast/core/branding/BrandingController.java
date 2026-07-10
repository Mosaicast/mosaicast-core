// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.web.NotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
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
    public ResponseEntity<byte[]> serve(@PathVariable String key, WebRequest request) {
        BrandingAsset asset = assetOf(key);
        BrandingService.Meta meta = branding.stat(asset);
        // checkNotModified handles the real If-None-Match grammar (weak W/"…", lists, *) and emits a 304
        // without loading the bytes.
        if (request.checkNotModified(meta.etag())) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(meta.etag())
                // Revalidate every time so a branding change propagates immediately (a cheap 304 when
                // unchanged); no stale window (ARCHITECTURE §12.2).
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(meta.mime()))
                // Raster/served-as-declared; nosniff (Spring Security default) blocks type confusion.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(branding.load(asset));
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
