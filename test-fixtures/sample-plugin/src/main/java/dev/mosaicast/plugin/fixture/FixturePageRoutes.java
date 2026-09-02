// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.PageRouteProvider;
import org.pf4j.Extension;

/**
 * The fixture's route provider (ARCHITECTURE §6.6, SDK {@code PageRouteProvider}) — what makes an unknown
 * subpath a real 404 instead of a soft one.
 *
 * <p>Answers for three shapes the host has to get right: the plugin root and a known page are pages, a
 * mistyped slug is not, and {@code boom} throws — because a broken provider must cost its own answer and
 * not turn a plugin's working pages into 404s.
 */
@Extension
public class FixturePageRoutes implements PageRouteProvider {

    /**
     * The routes this fixture claims.
     *
     * <p>Deliberately a superset of what it advertises elsewhere — its sitemap entries ({@code shared} and
     * its German twin {@code geteilt}, {@code reaching}, {@code plain}, {@code ctx-seen}) and its nav entry
     * ({@code _secret}) are all here. A plugin whose sitemap lists a URL its route provider denies would
     * make the two disagree about what exists, which is the contradiction §6.6 is about in the first place —
     * and a translation group makes that easy to get wrong, since every path in the group is a page.
     */
    @Override
    public boolean hasRoute(String subpath) {
        if ("boom".equals(subpath)) {
            throw new IllegalStateException("fixture route lookup failed");
        }
        return subpath.isEmpty()
                || "known".equals(subpath)
                || "_secret".equals(subpath)
                || "shared".equals(subpath)
                || "geteilt".equals(subpath)
                || "reaching".equals(subpath)
                || "plain".equals(subpath)
                || "ctx-seen".equals(subpath);
    }
}
