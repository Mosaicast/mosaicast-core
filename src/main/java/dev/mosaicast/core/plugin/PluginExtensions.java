// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.OgMeta;
import dev.mosaicast.plugin.api.ShareMetadataProvider;
import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The host's side of the two <em>optional</em> plugin extension points (ARCHITECTURE §7.4): share metadata
 * for a deep link (§6.4) and sitemap entries (§6.6). Most plugins implement neither, so "no provider" and
 * "provider found nothing" are both ordinary outcomes that fall back to site-level behaviour.
 *
 * <p>Every call into plugin code is isolated: a provider that throws is logged and skipped, exactly like a
 * scheduled task ({@link PluginScheduler}) — a plugin must not be able to break a page render or the sitemap.
 * Switched-off plugins are never asked, so disabling a plugin also removes its URLs from the sitemap.
 */
@Service
public class PluginExtensions {

    private static final Logger log = LoggerFactory.getLogger(PluginExtensions.class);

    private final PluginLoaderService plugins;

    public PluginExtensions(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    /**
     * Share metadata for {@code /p/{pluginId}/{subpath}}, or empty to fall back to site-level OpenGraph.
     * The first provider with an answer wins; a plugin declaring several is unusual but legal.
     *
     * @param pluginId the plugin owning the deep link
     * @param subpath  the path below {@code /p/{pluginId}/}; never null, may be empty (the plugin root)
     */
    public Optional<OgMeta> shareMetadata(String pluginId, String subpath) {
        for (ShareMetadataProvider provider : plugins.extensions(ShareMetadataProvider.class, pluginId)) {
            try {
                Optional<OgMeta> meta = provider.metaFor(subpath == null ? "" : subpath);
                if (meta != null && meta.isPresent()) {
                    return meta;
                }
            } catch (Exception e) {
                log.warn("ShareMetadataProvider of plugin '{}' failed for '{}': {}",
                        pluginId, subpath, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Sitemap entries contributed by every active plugin. Locations are validated to sit under that plugin's
     * own {@code /p/{pluginId}/} namespace — a plugin cannot inject URLs for the site or for another plugin.
     */
    public List<SitemapUrl> sitemapUrls() {
        List<SitemapUrl> urls = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            String pluginId = registration.id();
            String prefix = "/p/" + pluginId + "/";
            for (SitemapProvider provider : plugins.extensions(SitemapProvider.class, pluginId)) {
                try {
                    List<SitemapUrl> provided = provider.urls();
                    if (provided == null) {
                        continue;
                    }
                    provided.stream()
                            .filter(url -> url != null && url.loc() != null)
                            .filter(url -> url.loc().equals(prefix.substring(0, prefix.length() - 1))
                                    || url.loc().startsWith(prefix))
                            .forEach(urls::add);
                } catch (Exception e) {
                    log.warn("SitemapProvider of plugin '{}' failed: {}", pluginId, e.getMessage());
                }
            }
        }
        return urls;
    }
}
