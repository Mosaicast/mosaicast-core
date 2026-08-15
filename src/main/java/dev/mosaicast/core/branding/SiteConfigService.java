// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.web.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and updates the single {@link SiteConfig} row and derives the theme from its accent seed
 * (ARCHITECTURE §12.1/§12.3). Branding asset ids are set here when the admin uploads/clears an asset.
 */
@Service
public class SiteConfigService {

    private static final Logger log = LoggerFactory.getLogger(SiteConfigService.class);

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
        // Record what actually changed, old → new: "someone edited the site config" is not much use six
        // weeks later when the question is which setting started the problem.
        List<String> changes = new ArrayList<>();
        if (siteName != null && !siteName.isBlank() && !siteName.trim().equals(config.getSiteName())) {
            changes.add("name '%s' → '%s'".formatted(config.getSiteName(), siteName.trim()));
            config.setSiteName(siteName.trim());
        }
        if (accentSeed != null) {
            if (!HEX.matcher(accentSeed).matches()) {
                throw new IllegalArgumentException("Accent must be a #rrggbb hex colour");
            }
            if (!accentSeed.equalsIgnoreCase(config.getAccentSeed())) {
                changes.add("accent %s → %s".formatted(config.getAccentSeed(), accentSeed.toLowerCase()));
            }
            config.setAccentSeed(accentSeed.toLowerCase());
        }
        if (modePolicy != null && modePolicy != config.getModePolicy()) {
            changes.add("theme mode %s → %s".formatted(config.getModePolicy(), modePolicy));
            config.setModePolicy(modePolicy);
        }
        if (defaultLocale != null && !defaultLocale.isBlank()
                && !defaultLocale.trim().equalsIgnoreCase(config.getDefaultLocale())) {
            changes.add("default language %s → %s"
                    .formatted(config.getDefaultLocale(), defaultLocale.trim().toLowerCase()));
            config.setDefaultLocale(defaultLocale.trim().toLowerCase());
        }
        SiteConfig saved = configs.save(config);
        if (!changes.isEmpty()) {
            log.info("Site settings changed: {}", String.join(", ", changes));
        }
        return saved;
    }

    /**
     * Updates the AI-crawler policy served in {@code robots.txt} (ARCHITECTURE §6.6).
     *
     * <p>Deliberately its own method rather than two more parameters on {@link #update}: that one is the
     * branding/theme editor, and what a site says to crawlers is neither. Keeping them apart also keeps the
     * change log readable — "someone edited the site config" is already the thing that method's own comment
     * complains about.
     *
     * @param policy  the new policy, or {@code null} to leave it unchanged
     * @param blocked the explicit block list for {@link AiCrawlerPolicy#CUSTOM}, or {@code null} to leave it
     * @return the saved config
     */
    @Transactional
    public SiteConfig updateCrawlerPolicy(AiCrawlerPolicy policy, List<String> blocked) {
        SiteConfig config = get();
        List<String> changes = new ArrayList<>();
        if (policy != null && policy != config.getAiCrawlerPolicy()) {
            changes.add("AI crawler policy %s → %s".formatted(config.getAiCrawlerPolicy(), policy));
            config.setAiCrawlerPolicy(policy);
        }
        if (blocked != null) {
            // Normalized here rather than at the edge: these strings are compared against nothing and
            // written straight into robots.txt, so blank entries would emit a `User-agent:` with no agent.
            List<String> cleaned = blocked.stream()
                    .filter(agent -> agent != null && !agent.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!cleaned.equals(config.getAiCrawlerBlocked())) {
                changes.add("AI crawler block list %s → %s".formatted(config.getAiCrawlerBlocked(), cleaned));
                config.setAiCrawlerBlocked(cleaned);
            }
        }
        SiteConfig saved = configs.save(config);
        if (!changes.isEmpty()) {
            log.info("Site settings changed: {}", String.join(", ", changes));
        }
        return saved;
    }

    /** Points a branding slot at a stored blob (or {@code null} to fall back to the bundled default). */
    @Transactional
    public void setAsset(BrandingAsset asset, UUID blobId) {
        log.info("Branding {} {}", asset.name().toLowerCase(),
                blobId == null ? "cleared — falling back to the bundled default" : "set to blob " + blobId);
        SiteConfig config = get();
        switch (asset) {
            case LOGO -> config.setLogoAssetId(blobId);
            case FAVICON -> config.setFaviconAssetId(blobId);
            case DARK_LOGO -> config.setDarkLogoAssetId(blobId);
        }
        configs.save(config);
    }
}
