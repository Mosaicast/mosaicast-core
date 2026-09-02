// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.util.List;
import java.util.Map;
import org.pf4j.Extension;

/**
 * The fixture's optional sitemap extension (ARCHITECTURE §6.6/§7.4). Contributes one legal URL under its own
 * {@code /p/good/} namespace plus one that escapes it, so the host's test can prove the escaping entry is
 * dropped — a plugin must not be able to inject URLs for the site or for another plugin.
 *
 * <p>Since SDK 0.12.0 it also declares translation groups, in the three shapes the host has to handle: a
 * page translated at two different paths, a page whose alternate escapes the namespace (dropped, while the
 * page itself survives), and a page with nothing translated at all.
 */
@Extension
public class FixtureSitemap implements SitemapProvider {

    @Override
    public List<SitemapUrl> urls() {
        return List.of(
                // Translated slugs: one group, two paths — the case a list of locale codes could not express.
                new SitemapUrl("/p/good/shared", null,
                        Map.of("en", "/p/good/shared", "de", "/p/good/geteilt")),
                // A group reaching outside the plugin: the alternate must be dropped and the page kept.
                new SitemapUrl("/p/good/reaching", null,
                        Map.of("en", "/p/good/reaching", "de", "/episodes/not-mine")),
                // Nothing translated — the pre-0.12.0 shape, and still the honest answer for most pages.
                new SitemapUrl("/p/good/plain", null),
                new SitemapUrl("/episodes/not-mine", null));
    }
}
