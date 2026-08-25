// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.mosaicast.plugin.api.PageRouteProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code rendersRoute} on its own (ARCHITECTURE §6.6, SDK {@code PageRouteProvider}).
 *
 * <p>The end-to-end cases live in {@code PluginLoadingIntegrationTest}, against the fixture plugin. Two
 * cases cannot be expressed there and matter more than the rest: **a plugin with no provider at all**,
 * which is every plugin written before 0.9.1 and most plugins after it, and a provider that returns
 * nonsense. The fixtures share one jar, so every fixture plugin sees every extension in it — there is no
 * fixture without a provider to point at.
 */
@ExtendWith(MockitoExtension.class)
class PluginExtensionsRouteTest {

    @Mock
    private PluginLoaderService plugins;

    @Test
    void aPluginWithNoProviderStillRendersEverySubpath() {
        when(plugins.extensions(eq(PageRouteProvider.class), any())).thenReturn(List.of());

        // The compatibility promise this whole change rests on: absent means yes, so a plugin built before
        // the interface existed keeps answering 200 exactly as it did.
        assertThat(new PluginExtensions(plugins).rendersRoute("wiki", "anything")).isTrue();
    }

    @Test
    void aProviderDecidesPerSubpath() {
        when(plugins.extensions(eq(PageRouteProvider.class), any()))
                .thenReturn(List.of(subpath -> "kraken".equals(subpath)));
        PluginExtensions extensions = new PluginExtensions(plugins);

        assertThat(extensions.rendersRoute("wiki", "kraken")).isTrue();
        assertThat(extensions.rendersRoute("wiki", "nowhere")).isFalse();
    }

    @Test
    void aNullSubpathIsThePluginRoot() {
        when(plugins.extensions(eq(PageRouteProvider.class), any()))
                .thenReturn(List.of(subpath -> subpath.isEmpty()));

        // The contract says a provider never receives null — so the host is the one that has to guarantee
        // it, rather than every plugin author defending against it.
        assertThat(new PluginExtensions(plugins).rendersRoute("wiki", null)).isTrue();
    }

    @Test
    void aThrowingProviderLeavesTheRouteAsItWas() {
        when(plugins.extensions(eq(PageRouteProvider.class), any()))
                .thenReturn(List.of(subpath -> {
                    throw new IllegalStateException("provider is broken");
                }));

        // Sharper here than for the other extension points: a broken provider costs its own answer, and
        // must not be able to turn a plugin's working pages into 404s.
        assertThat(new PluginExtensions(plugins).rendersRoute("wiki", "kraken")).isTrue();
    }

    @Test
    void oneProviderSayingNoIsEnough() {
        when(plugins.extensions(eq(PageRouteProvider.class), any()))
                .thenReturn(List.of(subpath -> true, subpath -> false));

        // A plugin declaring several is unusual but legal, and "no" is the answer that carries information:
        // one provider claiming everything would otherwise mask the one that knows.
        assertThat(new PluginExtensions(plugins).rendersRoute("wiki", "kraken")).isFalse();
    }
}
