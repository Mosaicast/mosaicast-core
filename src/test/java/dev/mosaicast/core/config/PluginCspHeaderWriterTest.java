// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The CSP is widened by exactly what plugins declared (ARCHITECTURE §12.5): the manifest declaration is both
 * the notice shown to visitors and the permission the browser enforces.
 *
 * <p>The second half covers {@code img-src}/{@code media-src}, which are the one place the policy is
 * deliberately wide by default and can be narrowed on request.
 */
class PluginCspHeaderWriterTest {

    @Test
    void withoutDeclarationsThePolicyStaysSelfOnly() {
        String policy = PluginCspHeaderWriter.policy(Set.of(), null);
        assertThat(policy).contains("default-src 'self'").contains("script-src 'self';");
        assertThat(policy).contains("frame-ancestors 'none'");
    }

    @Test
    void declaredHostsAreAddedToTheLoadingDirectives() {
        String policy = PluginCspHeaderWriter.policy(
                new LinkedHashSet<>(Set.of("https://plausible.example")), null);
        assertThat(policy)
                .contains("script-src 'self' https://plausible.example")
                .contains("frame-src 'self' https://plausible.example")
                .contains("connect-src 'self' https://plausible.example");
        // Not everything is widened: the document itself stays locked down.
        assertThat(policy).contains("default-src 'self';").contains("object-src 'none'");
    }

    @Test
    void aHostThatCouldBreakOutOfTheHeaderIsDropped() {
        // A manifest value ends up in a response header; a separator disqualifies the entry rather than
        // being escaped, so a plugin cannot inject directives of its own.
        String policy = PluginCspHeaderWriter.policy(
                new LinkedHashSet<>(Set.of("evil.example; script-src *")), null);
        assertThat(policy).doesNotContain("evil.example");
        assertThat(policy).contains("script-src 'self';");
    }

    @Test
    void byDefaultImagesAndMediaComeFromAnyHttpsHost() {
        // The default, and the residual it carries: artwork comes from whatever host a feed points at, so
        // an <img> to any origin is a working one-way beacon past connect-src and past consent.
        String policy = PluginCspHeaderWriter.policy(Set.of(), null);
        assertThat(policy).contains("img-src 'self' data: https:;").contains("media-src 'self' https:;");
    }

    @Test
    void strictModeNarrowsImagesAndMediaToTheOriginsInUse() {
        String policy = PluginCspHeaderWriter.policy(
                Set.of(), new LinkedHashSet<>(Set.of("https://cdn.example.com")));

        assertThat(policy)
                .contains("img-src 'self' data: https://cdn.example.com;")
                .contains("media-src 'self' https://cdn.example.com;");
        // The blanket is gone — which is the whole point — and `data:` is not, since a data URI reaches
        // nobody and the shell inlines small assets.
        assertThat(policy).doesNotContain("https:;");
    }

    @Test
    void strictModeWithNothingInUseAllowsNoExternalImageAtAll() {
        // A fresh install with no feeds. Empty is the correct answer rather than a fallback to the blanket:
        // an operator who switched this on asked for the closed one.
        String policy = PluginCspHeaderWriter.policy(Set.of(), Set.of());
        assertThat(policy).contains("img-src 'self' data:;").contains("media-src 'self';");
    }

    @Test
    void strictModeStillHonoursTheHostsThisVisitorConsentedTo() {
        // A plugin's declared image host is subject to the same consent narrowing as its script host —
        // which is the half of this the blanket `https:` made meaningless.
        String policy = PluginCspHeaderWriter.policy(
                new LinkedHashSet<>(Set.of("https://plausible.example")),
                new LinkedHashSet<>(Set.of("https://cdn.example.com")));

        assertThat(policy).contains("img-src 'self' data: https://cdn.example.com https://plausible.example;");
    }

    @Test
    void aMalformedDerivedOriginIsDroppedFromTheMediaDirectivesToo() {
        String policy = PluginCspHeaderWriter.policy(
                Set.of(), new LinkedHashSet<>(Set.of("cdn.example; img-src *")));
        assertThat(policy).doesNotContain("cdn.example");
        assertThat(policy).contains("img-src 'self' data:;");
    }

    @Test
    void onlyHttpsOriginsAreDerivedFromContentUrls() {
        assertThat(ExternalMediaHostRegistry.originOf("https://cdn.example.com/a/b.jpg"))
                .isEqualTo("https://cdn.example.com");
        // Port is part of the origin; host case is not.
        assertThat(ExternalMediaHostRegistry.originOf("https://CDN.Example.com:8443/a.mp3"))
                .isEqualTo("https://cdn.example.com:8443");
        // Plaintext is dropped rather than allowed: the browser would refuse it as mixed content anyway,
        // and naming it in the policy would suggest otherwise.
        assertThat(ExternalMediaHostRegistry.originOf("http://cdn.example.com/a.jpg")).isNull();
        assertThat(ExternalMediaHostRegistry.originOf("not a url at all")).isNull();
        assertThat(ExternalMediaHostRegistry.originOf("")).isNull();
        assertThat(ExternalMediaHostRegistry.originOf(null)).isNull();
    }
}
