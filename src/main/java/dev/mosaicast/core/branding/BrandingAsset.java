// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import java.util.Optional;

/**
 * The brandable site assets (ARCHITECTURE §12.1/§12.2). Each maps to a key in the {@code branding} blob
 * namespace, a served URL path, and a bundled classpath default used until an admin uploads a custom one.
 */
public enum BrandingAsset {

    LOGO("logo", "branding/mosaicast-logo.svg"),
    FAVICON("favicon", "branding/mosaicast-mark.svg"),
    DARK_LOGO("dark-logo", "branding/mosaicast-logo.svg");

    /** Blob key within the {@code branding} namespace and the {@code /branding/<key>} URL segment. */
    private final String key;
    /** Classpath resource for the bundled default (a trusted SVG). */
    private final String defaultResource;

    BrandingAsset(String key, String defaultResource) {
        this.key = key;
        this.defaultResource = defaultResource;
    }

    public String key() {
        return key;
    }

    public String defaultResource() {
        return defaultResource;
    }

    /** Resolves a {@code /branding/<key>} path segment to an asset. */
    public static Optional<BrandingAsset> fromKey(String key) {
        for (BrandingAsset asset : values()) {
            if (asset.key.equals(key)) {
                return Optional.of(asset);
            }
        }
        return Optional.empty();
    }
}
