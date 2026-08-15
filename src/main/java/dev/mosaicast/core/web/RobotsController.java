// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import dev.mosaicast.core.branding.AiCrawlerCatalog;
import dev.mosaicast.core.branding.AiCrawlerPolicy;
import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code robots.txt} (ARCHITECTURE §6.6): what the site asks crawlers to skip, where its sitemap is, and
 * the admin-configured AI-crawler policy.
 *
 * <p>Served rather than shipped as a static file, because two thirds of it are not static: the sitemap URL
 * comes from configuration ({@link SiteUrls}) and the AI-crawler section is an admin setting. §6.6 is
 * explicit that the AI policy is "an admin setting, not hardcoded — operators decide", so core ships the
 * mechanism and a catalog ({@link AiCrawlerCatalog}) and takes no position.
 *
 * <p><strong>What the disallow rules are for.</strong> The admin and API paths listed here are not secret
 * and are not protected by being listed — they are protected by Spring Security, which answers a crawler
 * exactly as it answers anyone else. Keeping them out of a crawl budget and out of a search index is the
 * entire point; a {@code Disallow} line is a request to well-behaved software and nothing more. The same
 * caveat applies with more force to the AI section, and the admin panel says so, because a setting that
 * reads like a lock and is not one is worse than no setting at all.
 */
@RestController
public class RobotsController {

    private final SiteConfigService siteConfig;
    private final SiteUrls urls;

    public RobotsController(SiteConfigService siteConfig, SiteUrls urls) {
        this.siteConfig = siteConfig;
        this.urls = urls;
    }

    @GetMapping(path = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots() {
        SiteConfig config = siteConfig.get();
        StringBuilder txt = new StringBuilder();

        txt.append("User-agent: *\n");
        txt.append("Disallow: /api/\n");
        txt.append("Disallow: /admin\n");
        txt.append("Disallow: /actuator/\n");
        txt.append('\n');

        for (String agent : blockedAgents(config)) {
            txt.append("User-agent: ").append(agent).append('\n');
            txt.append("Disallow: /\n");
            txt.append('\n');
        }

        // Absolute, and from configuration rather than the request — see SiteUrls for why that matters.
        txt.append("Sitemap: ").append(urls.absolute("/sitemap.xml")).append('\n');
        return txt.toString();
    }

    /** The agents this site asks not to crawl it, per the configured policy. */
    private static List<String> blockedAgents(SiteConfig config) {
        AiCrawlerPolicy policy = config.getAiCrawlerPolicy();
        if (policy == AiCrawlerPolicy.BLOCK) {
            return AiCrawlerCatalog.agents();
        }
        if (policy == AiCrawlerPolicy.CUSTOM) {
            // Sanitized on the way in (SiteConfigService.updateCrawlerPolicy), but a newline reaching here
            // would let a stored value inject its own directives, so the one character that could is
            // refused at the point of writing rather than trusted from the row.
            return config.getAiCrawlerBlocked().stream()
                    .filter(agent -> agent != null && !agent.isBlank())
                    .map(agent -> agent.replaceAll("[\\r\\n]", " ").trim())
                    .toList();
        }
        return List.of();
    }
}
