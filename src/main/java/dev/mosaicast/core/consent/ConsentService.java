// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.legal.LegalService;
import dev.mosaicast.core.legal.LegalViews.FooterEntry;
import dev.mosaicast.core.plugin.PluginManifest;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.core.plugin.PluginLoaderService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * The platform consent service (ARCHITECTURE §12.5). The core itself sets only strictly necessary and
 * functional client storage, so <strong>it runs banner-free</strong>: consent exists solely because a plugin
 * may load third-party content that sets cookies. The host aggregates what plugins declare in their manifests
 * — categories plus external sources — and the shell asks for consent only when that aggregate is non-empty.
 *
 * <p>Only <em>active</em> plugins count: switching a plugin off removes its categories, and with them the
 * banner, without touching anything else.
 */
@Service
public class ConsentService {

    /**
     * Categories the host understands. {@code necessary} is never prompted for (it is what the core itself
     * uses); anything else a manifest declares is passed through as a plugin-declared category, so a plugin
     * is not limited to this vocabulary — the shell just has no translated label for it.
     */
    public static final String CATEGORY_NECESSARY = "necessary";
    public static final String CATEGORY_FUNCTIONAL = "functional";
    public static final String CATEGORY_ANALYTICS = "analytics";
    public static final Set<String> KNOWN_CATEGORIES =
            Set.of(CATEGORY_NECESSARY, CATEGORY_FUNCTIONAL, CATEGORY_ANALYTICS);

    private final PluginLoaderService plugins;
    private final LegalService legal;
    private final SiteConfigService siteConfig;

    public ConsentService(PluginLoaderService plugins, LegalService legal, SiteConfigService siteConfig) {
        this.plugins = plugins;
        this.legal = legal;
        this.siteConfig = siteConfig;
    }

    /**
     * What the shell needs to decide whether to ask, and what to ask about.
     *
     * @param categories   consent categories declared by active plugins, {@code necessary} excluded (it is
     *                     never optional); empty means <em>no banner</em>
     * @param sources      the declared third-party hosts, for the generated notice and the admin audit
     * @param privacySlug  the legal page marked {@code privacy} (§12.6) the banner links to, or {@code null}
     */
    public record ConsentView(List<CategoryView> categories, List<SourceView> sources, String privacySlug) {
    }

    /** One category and which plugins asked for it — the notice has to say who wants what. */
    public record CategoryView(String category, List<String> pluginIds, boolean known) {
    }

    /** One declared third-party host and the plugin that declared it. */
    public record SourceView(String source, String pluginId) {
    }

    /** The aggregate over all active plugins. */
    public ConsentView current() {
        List<CategoryView> categories = new ArrayList<>();
        List<SourceView> sources = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        for (PluginRegistration registration : plugins.allActive()) {
            PluginManifest.Consent consent = registration.manifest() == null
                    ? null : registration.manifest().consent();
            if (consent == null) {
                continue;
            }
            if (consent.categories() != null) {
                for (String raw : consent.categories()) {
                    String category = normalize(raw);
                    if (category == null || CATEGORY_NECESSARY.equals(category)) {
                        continue;
                    }
                    if (seen.add(category)) {
                        categories.add(new CategoryView(category, new ArrayList<>(),
                                KNOWN_CATEGORIES.contains(category)));
                    }
                    categories.stream()
                            .filter(c -> c.category().equals(category))
                            .findFirst()
                            .ifPresent(c -> c.pluginIds().add(registration.id()));
                }
            }
            if (consent.externalSources() != null) {
                consent.externalSources().stream()
                        .filter(source -> source != null && !source.isBlank())
                        .forEach(source -> sources.add(new SourceView(source.trim(), registration.id())));
            }
        }
        return new ConsentView(categories, sources, privacySlug());
    }

    /**
     * Every distinct third-party host active plugins declared — the allow-list the CSP is widened by
     * ({@link dev.mosaicast.core.config.PluginCspHeaderWriter}). A plugin that declares nothing cannot load
     * anything third-party, which is the point: the declaration is both the notice and the permission.
     */
    public Set<String> declaredExternalSources() {
        Set<String> hosts = new LinkedHashSet<>();
        current().sources().forEach(source -> hosts.add(source.source()));
        return hosts;
    }

    private String privacySlug() {
        return legal.footer(siteConfig.get().getDefaultLocale()).stream()
                .filter(entry -> "privacy".equalsIgnoreCase(entry.role()))
                .map(FooterEntry::slug)
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String category) {
        return category == null || category.isBlank() ? null : category.trim().toLowerCase();
    }
}
