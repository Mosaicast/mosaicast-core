// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.util.List;
import org.pf4j.Extension;

/**
 * The fixture's optional sitemap extension (ARCHITECTURE §6.6/§7.4). Contributes one legal URL under its own
 * {@code /p/good/} namespace plus one that escapes it, so the host's test can prove the escaping entry is
 * dropped — a plugin must not be able to inject URLs for the site or for another plugin.
 */
@Extension
public class FixtureSitemap implements SitemapProvider {

    @Override
    public List<SitemapUrl> urls() {
        return List.of(
                new SitemapUrl("/p/good/shared", null),
                new SitemapUrl("/episodes/not-mine", null));
    }
}
