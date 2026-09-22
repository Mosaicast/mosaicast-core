// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The subpath a deep link hands a plugin (ARCHITECTURE §6.4).
 *
 * <p>Its own test because the value is what a plugin's {@code PageRouteProvider} and
 * {@code ShareMetadataProvider} are asked about, and the integration fixtures share one jar — there is no
 * fixture whose provider could report back what it was given.
 */
class PluginPageSubpathTest {

    @Test
    void theRootIsAnEmptySubpath() {
        assertThat(PluginPageController.subpathOf("/p/wiki", "wiki")).isEmpty();
        assertThat(PluginPageController.subpathOf("/p/wiki/", "wiki")).isEmpty();
    }

    @Test
    void aPlainPathIsHandedOverAsWritten() {
        assertThat(PluginPageController.subpathOf("/p/wiki/kraken/notes", "wiki")).isEqualTo("kraken/notes");
    }

    @Test
    void aPercentEncodedPathReachesThePluginDecoded() {
        // getRequestURI() is, per the servlet spec, not decoded, so the server-side half saw
        // `caf%C3%A9-episode` while the browser hands the same plugin `café-episode` through ctx.route.
        // For the wiki, whose slugs come from page titles, that meant every deep link to a page with a
        // non-ASCII character 404'd on a hard navigation and worked once the SPA had booted (core#181).
        assertThat(PluginPageController.subpathOf("/p/wiki/caf%C3%A9-episode", "wiki"))
                .isEqualTo("café-episode");
        assertThat(PluginPageController.subpathOf("/p/wiki/a%20b/c", "wiki")).isEqualTo("a b/c");
    }

    @Test
    void anEncodedSlashDecodesToOneAndTheDistinctionIsLost() {
        // Pinned because it is a limit, not a feature. The subpath is handed over as one flat string, so
        // `a%2Fb` — one segment containing a slash — is indistinguishable from `a/b` once decoded, and a
        // plugin that splits on '/' sees two segments either way. Decoding per segment keeps the *host*
        // from re-splitting on it, which is why the two are separate steps, but nothing here can carry the
        // difference to the plugin. Expressing it would take a shape change on the SDK side.
        assertThat(PluginPageController.subpathOf("/p/wiki/a%2Fb", "wiki")).isEqualTo("a/b");
        assertThat(PluginPageController.subpathOf("/p/wiki/a/b", "wiki")).isEqualTo("a/b");
    }
}
