// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Site branding + theme (ARCHITECTURE §12.1). A single row (id pinned to {@code 1}), editable by ADMIN,
 * so no config files are needed. Assets are referenced by blob id; a null id means "use the bundled
 * default". The accent seed drives the whole theme (§12.3).
 */
@Entity
@Table(name = "site_config")
public class SiteConfig {

    /** The single-row id (the DB enforces {@code id = 1}). */
    public static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(name = "site_name", nullable = false)
    private String siteName;

    @Column(name = "logo_asset_id")
    private UUID logoAssetId;

    @Column(name = "favicon_asset_id")
    private UUID faviconAssetId;

    @Column(name = "dark_logo_asset_id")
    private UUID darkLogoAssetId;

    @Column(name = "accent_seed", nullable = false)
    private String accentSeed;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_policy", nullable = false)
    private ModePolicy modePolicy = ModePolicy.SYSTEM;

    /**
     * The site default language (ARCHITECTURE §12.7): the fallback for legal pages with no translation in the
     * requested locale, and the initial UI language when a visitor's browser language isn't one we ship.
     */
    @Column(name = "default_locale", nullable = false)
    private String defaultLocale = "en";

    /** What {@code robots.txt} says to AI crawlers (ARCHITECTURE §6.6) — the operator's call, not ours. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_crawler_policy", nullable = false)
    private AiCrawlerPolicy aiCrawlerPolicy = AiCrawlerPolicy.ALLOW;

    /** The user-agent tokens disallowed under {@link AiCrawlerPolicy#CUSTOM}; ignored otherwise. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_crawler_blocked", nullable = false)
    private List<String> aiCrawlerBlocked = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SiteConfig() {
        // for JPA
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
        touch();
    }

    public void setAccentSeed(String accentSeed) {
        this.accentSeed = accentSeed;
        touch();
    }

    public void setModePolicy(ModePolicy modePolicy) {
        this.modePolicy = modePolicy;
        touch();
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
        touch();
    }

    public void setLogoAssetId(UUID logoAssetId) {
        this.logoAssetId = logoAssetId;
        touch();
    }

    public void setFaviconAssetId(UUID faviconAssetId) {
        this.faviconAssetId = faviconAssetId;
        touch();
    }

    public void setDarkLogoAssetId(UUID darkLogoAssetId) {
        this.darkLogoAssetId = darkLogoAssetId;
        touch();
    }

    public void setAiCrawlerPolicy(AiCrawlerPolicy aiCrawlerPolicy) {
        this.aiCrawlerPolicy = aiCrawlerPolicy;
        touch();
    }

    /** Replaces the block list wholesale — it is edited as one form field, not entry by entry. */
    public void setAiCrawlerBlocked(List<String> aiCrawlerBlocked) {
        this.aiCrawlerBlocked = aiCrawlerBlocked == null ? new ArrayList<>() : new ArrayList<>(aiCrawlerBlocked);
        touch();
    }

    public String getSiteName() {
        return siteName;
    }

    public UUID getLogoAssetId() {
        return logoAssetId;
    }

    public UUID getFaviconAssetId() {
        return faviconAssetId;
    }

    public UUID getDarkLogoAssetId() {
        return darkLogoAssetId;
    }

    public String getAccentSeed() {
        return accentSeed;
    }

    public ModePolicy getModePolicy() {
        return modePolicy;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public AiCrawlerPolicy getAiCrawlerPolicy() {
        return aiCrawlerPolicy;
    }

    public List<String> getAiCrawlerBlocked() {
        return aiCrawlerBlocked == null ? List.of() : aiCrawlerBlocked;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
