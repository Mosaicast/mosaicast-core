// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.web.NotFoundException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and updates the single {@link SiteConfig} row and derives the theme from its accent seed
 * (ARCHITECTURE §12.1/§12.3). Branding asset ids are set here when the admin uploads/clears an asset.
 */
@Service
public class SiteConfigService {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final SiteConfigRepository configs;
    private final ThemeSeedGenerator themeGenerator;

    public SiteConfigService(SiteConfigRepository configs, ThemeSeedGenerator themeGenerator) {
        this.configs = configs;
        this.themeGenerator = themeGenerator;
    }

    @Transactional(readOnly = true)
    public SiteConfig get() {
        return configs.findById(SiteConfig.SINGLETON_ID)
                .orElseThrow(() -> new NotFoundException("Site config missing (should be seeded)"));
    }

    /** The full generated theme for the current accent seed. */
    @Transactional(readOnly = true)
    public GeneratedTheme theme() {
        return themeGenerator.generate(get().getAccentSeed());
    }

    /** Updates admin-editable settings; any {@code null} field is left unchanged. Validates the accent. */
    @Transactional
    public SiteConfig update(String siteName, String accentSeed, ModePolicy modePolicy, String defaultLocale) {
        SiteConfig config = get();
        if (siteName != null && !siteName.isBlank()) {
            config.setSiteName(siteName.trim());
        }
        if (accentSeed != null) {
            if (!HEX.matcher(accentSeed).matches()) {
                throw new IllegalArgumentException("Accent must be a #rrggbb hex colour");
            }
            config.setAccentSeed(accentSeed.toLowerCase());
        }
        if (modePolicy != null) {
            config.setModePolicy(modePolicy);
        }
        if (defaultLocale != null && !defaultLocale.isBlank()) {
            config.setDefaultLocale(defaultLocale.trim().toLowerCase());
        }
        return configs.save(config);
    }

    /** Points a branding slot at a stored blob (or {@code null} to fall back to the bundled default). */
    @Transactional
    public void setAsset(BrandingAsset asset, UUID blobId) {
        SiteConfig config = get();
        switch (asset) {
            case LOGO -> config.setLogoAssetId(blobId);
            case FAVICON -> config.setFaviconAssetId(blobId);
            case DARK_LOGO -> config.setDarkLogoAssetId(blobId);
        }
        configs.save(config);
    }
}
