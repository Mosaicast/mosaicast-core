// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which dark logo the shell is told about (ARCHITECTURE §12.2): one that fits the logo in use, or none.
 */
class SiteViewTest {

    private static String darkLogo(UUID logo, UUID dark) {
        SiteConfig config = new SiteConfig();
        config.setLogoAssetId(logo);
        config.setDarkLogoAssetId(dark);
        return SiteView.of(config, null).branding().darkLogo();
    }

    @Test
    void theBundledLogoComesWithItsBundledDarkVariant() {
        // The light wordmark's dark lettering is barely visible on a dark background.
        assertThat(darkLogo(null, null)).isEqualTo("/branding/dark-logo");
        assertThat(BrandingAsset.DARK_LOGO.defaultResource()).isNotEqualTo(BrandingAsset.LOGO.defaultResource());
    }

    @Test
    void anUploadedLogoWithoutADarkOneIsShownInBothModes() {
        // Not ours in dark mode and theirs in light mode.
        assertThat(darkLogo(UUID.randomUUID(), null)).isNull();
    }

    @Test
    void anUploadedDarkLogoIsAlwaysUsed() {
        assertThat(darkLogo(UUID.randomUUID(), UUID.randomUUID())).isEqualTo("/branding/dark-logo");
        assertThat(darkLogo(null, UUID.randomUUID())).isEqualTo("/branding/dark-logo");
    }

    @Test
    void theBundledDarkVariantIsOnTheClasspath() {
        assertThat(getClass().getClassLoader().getResource(BrandingAsset.DARK_LOGO.defaultResource())).isNotNull();
    }
}
