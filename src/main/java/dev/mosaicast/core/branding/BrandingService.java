// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.blob.BlobMetadata;
import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStore;
import dev.mosaicast.core.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads, serves and clears branding assets (ARCHITECTURE §12.2). Custom assets live in the
 * {@code branding} blob namespace and are referenced from {@link SiteConfig}; when none is set, the
 * bundled default (a trusted classpath SVG) is served.
 *
 * <p><strong>SVG is an XSS vector</strong>, so uploads are restricted to raster formats (PNG/ICO/JPEG/WEBP)
 * — the conservative half of §12.2's "sanitize or restrict to raster". The bundled defaults are our own
 * trusted SVGs; only untrusted uploads are constrained.
 */
@Service
public class BrandingService {

    private static final String NAMESPACE = "branding";
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024; // 2 MB

    /** Raster types accepted for upload; SVG and anything else is rejected. */
    private static final Set<String> ALLOWED_UPLOAD_MIMES = Set.of(
            "image/png", "image/x-icon", "image/vnd.microsoft.icon", "image/jpeg", "image/webp");

    private final BlobStore blobStore;
    private final SiteConfigService site;

    public BrandingService(BlobStore blobStore, SiteConfigService site) {
        this.blobStore = blobStore;
        this.site = site;
    }

    /** An asset's content type + ETag, resolved without loading the bytes (so a 304 is cheap). */
    public record Meta(String mime, String etag) {
    }

    /**
     * Resolves an asset's content type + ETag from metadata only — no bytes. Lets the controller answer a
     * conditional GET (304) without reading the blob (ARCHITECTURE §12.2).
     */
    public Meta stat(BrandingAsset asset) {
        UUID blobId = assetId(asset);
        if (blobId != null) {
            Optional<BlobMetadata> meta = blobStore.stat(new BlobRef(blobId, NAMESPACE));
            if (meta.isPresent()) {
                Instant updated = meta.get().updatedAt();
                return new Meta(meta.get().mime(), etag(Long.toHexString(updated.toEpochMilli())));
            }
        }
        return new Meta("image/svg+xml", etag("default-" + asset.key()));
    }

    /**
     * Loads an asset's bytes: the custom blob if set, otherwise the bundled default. If the custom blob
     * vanished since {@link #stat} (a concurrent clear), it falls back to the default rather than 404 —
     * the serve endpoint must never fail.
     */
    public byte[] load(BrandingAsset asset) {
        UUID blobId = assetId(asset);
        if (blobId != null) {
            try {
                return readAll(blobStore.get(new BlobRef(blobId, NAMESPACE)).stream());
            } catch (NotFoundException e) {
                // Blob deleted between stat and load — serve the bundled default.
            }
        }
        return readClasspath(asset.defaultResource());
    }

    /** Stores an uploaded asset (raster only) and points the site config at it. */
    public void upload(BrandingAsset asset, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Branding upload exceeds 2 MB");
        }
        String mime = file.getContentType();
        if (mime == null || !ALLOWED_UPLOAD_MIMES.contains(mime.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Only raster images (PNG, ICO, JPEG, WEBP) may be uploaded; SVG is not allowed");
        }
        try (InputStream in = file.getInputStream()) {
            BlobRef ref = blobStore.put(NAMESPACE, asset.key(), in, mime.toLowerCase());
            site.setAsset(asset, ref.id());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload", e);
        }
    }

    /** Clears a custom asset, reverting to the bundled default. */
    public void clear(BrandingAsset asset) {
        UUID blobId = assetId(asset);
        if (blobId != null) {
            blobStore.delete(new BlobRef(blobId, NAMESPACE));
        }
        site.setAsset(asset, null);
    }

    private UUID assetId(BrandingAsset asset) {
        SiteConfig config = site.get();
        return switch (asset) {
            case LOGO -> config.getLogoAssetId();
            case FAVICON -> config.getFaviconAssetId();
            case DARK_LOGO -> config.getDarkLogoAssetId();
        };
    }

    private static String etag(String raw) {
        return "\"" + raw + "\"";
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob", e);
        }
    }

    private static byte[] readClasspath(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing bundled branding default: " + resource, e);
        }
    }
}
