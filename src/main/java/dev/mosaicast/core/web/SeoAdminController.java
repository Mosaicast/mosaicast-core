// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import dev.mosaicast.core.branding.AiCrawlerCatalog;
import dev.mosaicast.core.branding.AiCrawlerPolicy;
import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin editor for the AI-crawler policy (ARCHITECTURE §6.6). ADMIN-only, enforced by the
 * {@code /api/admin/**} rule in {@code SecurityConfig}.
 *
 * <p>Separate from {@code SiteController} on purpose: that one is branding and theme, and what a site asks
 * of crawlers is a different decision made by a different person on a different day. The response carries
 * core's catalog alongside the current setting so the panel can render one checkbox per known agent without
 * shipping a second copy of the list in the frontend — a duplicated list is a list that goes stale.
 */
@RestController
public class SeoAdminController {

    private final SiteConfigService site;

    public SeoAdminController(SiteConfigService site) {
        this.site = site;
    }

    /**
     * The crawler policy plus the catalog it is chosen from.
     *
     * @param policy  {@code allow}, {@code block} or {@code custom}
     * @param blocked the explicit block list, meaningful under {@code custom}
     * @param known   core's catalog of AI crawlers, for the panel
     */
    public record SeoView(String policy, List<String> blocked, List<AiCrawlerCatalog.Crawler> known) {

        static SeoView of(SiteConfig config) {
            return new SeoView(
                    config.getAiCrawlerPolicy().name().toLowerCase(Locale.ROOT),
                    config.getAiCrawlerBlocked(),
                    AiCrawlerCatalog.all());
        }
    }

    /** Admin edit. An omitted field is left unchanged. */
    public record UpdateSeo(String policy, List<String> blocked) {
    }

    @GetMapping("/api/admin/seo")
    public SeoView get() {
        return SeoView.of(site.get());
    }

    @PutMapping("/api/admin/seo")
    public SeoView update(@RequestBody UpdateSeo request) {
        return SeoView.of(site.updateCrawlerPolicy(parsePolicy(request.policy()), request.blocked()));
    }

    private static AiCrawlerPolicy parsePolicy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AiCrawlerPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("policy must be one of allow, block, custom");
        }
    }
}
