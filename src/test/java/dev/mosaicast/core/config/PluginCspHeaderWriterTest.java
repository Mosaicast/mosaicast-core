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
 */
class PluginCspHeaderWriterTest {

    @Test
    void withoutDeclarationsThePolicyStaysSelfOnly() {
        String policy = PluginCspHeaderWriter.policy(Set.of());
        assertThat(policy).contains("default-src 'self'").contains("script-src 'self';");
        assertThat(policy).contains("frame-ancestors 'none'");
    }

    @Test
    void declaredHostsAreAddedToTheLoadingDirectives() {
        String policy = PluginCspHeaderWriter.policy(new LinkedHashSet<>(Set.of("https://plausible.example")));
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
        String policy = PluginCspHeaderWriter.policy(new LinkedHashSet<>(Set.of("evil.example; script-src *")));
        assertThat(policy).doesNotContain("evil.example");
        assertThat(policy).contains("script-src 'self';");
    }
}
