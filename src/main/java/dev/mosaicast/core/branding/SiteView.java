// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import java.util.Locale;

/**
 * The public site payload returned at boot (ARCHITECTURE §12.1): name, mode policy, the accent seed and
 * its generated light/dark tokens, and the branding asset URLs. The shell applies the tokens before first
 * paint (no-flash) and points its logo/favicon at these URLs.
 *
 * @param name          the site name
 * @param modePolicy    {@code light} / {@code dark} / {@code system}
 * @param accentSeed    the accent {@code #rrggbb}
 * @param defaultLocale the site default language (UI + legal fallback, §12.7)
 * @param theme         generated light + dark token sets
 * @param branding      asset URLs
 */
public record SiteView(String name, String modePolicy, String accentSeed, String defaultLocale,
                       GeneratedTheme theme, Branding branding) {

    /**
     * Branding asset URLs. Logo/favicon always resolve (they fall back to bundled defaults). The dark logo is
     * there when it fits the logo in use: the uploaded one, or — while the logo is still the bundled one —
     * the bundled dark variant, whose lettering stays readable on a dark background. A site with its own logo
     * and no dark one gets {@code null} and shows its own logo in both modes, rather than ours in one.
     */
    public record Branding(String logo, String favicon, String darkLogo) {
    }

    public static SiteView of(SiteConfig config, GeneratedTheme theme) {
        boolean darkFitsTheLogo = config.getDarkLogoAssetId() != null || config.getLogoAssetId() == null;
        String darkLogo = darkFitsTheLogo ? "/branding/dark-logo" : null;
        return new SiteView(
                config.getSiteName(),
                config.getModePolicy().name().toLowerCase(Locale.ROOT),
                config.getAccentSeed(),
                config.getDefaultLocale(),
                theme,
                new Branding("/branding/logo", "/branding/favicon", darkLogo));
    }
}
