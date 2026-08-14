// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import dev.mosaicast.core.consent.ConsentCookie;
import dev.mosaicast.core.consent.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.stereotype.Component;

/**
 * Writes the Content-Security-Policy, widened by exactly the third-party hosts active plugins
 * <em>declared</em> in their manifests (ARCHITECTURE §12.5, §13).
 *
 * <p>The base policy is {@code default-src 'self'}, which is what keeps the core honest — but a plugin that
 * legitimately embeds third-party content (the click-to-load case consent exists for) would be blocked by it.
 * Rather than loosening the policy for everyone, the manifest declaration doubles as the permission: what a
 * plugin declares is what the notice tells visitors about *and* the only thing the browser will load. An
 * undeclared host stays blocked even if the plugin tries.
 *
 * <p><strong>Narrowed by what this visitor consented to</strong>, read from {@link ConsentCookie}. That is
 * what makes a refusal enforceable rather than merely promised: {@code ctx.consent.has()} is advisory — a
 * plugin shares the page's JavaScript realm and can simply not call it — but an origin the browser refuses
 * to connect to is not advisory. A declined category takes its origins out of the policy, and the request
 * fails at the network layer instead of relying on plugin manners. Before a decision exists, nothing
 * optional is allowed, matching the client's default-deny.
 *
 * <p><strong>Images and media are the exception</strong>, and knowingly so: {@code img-src}/{@code media-src}
 * carry a blanket {@code https:} because feed artwork and audio come from whatever host the podcaster's feed
 * points at. That leaves an {@code <img>} to any origin working as a one-way beacon, past
 * {@code connect-src 'self'} and past consent. {@code mosaicast.security.strict-media-sources} replaces the
 * blanket with the origins the site's own content actually references
 * ({@link ExternalMediaHostRegistry}) plus the consented plugin hosts. Off by default, because a derivation
 * cannot see a host nothing references yet.
 *
 * <p>Recomputed per request from {@link ConsentService}, so switching a plugin off — or withdrawing consent —
 * narrows the policy again without a restart. The cost is a cheap manifest scan. The cookie value is
 * visitor-controlled, which is not a hole: it can only widen that visitor's own policy, and CSP protects
 * exactly the visitor whose browser enforces it. Each value is validated as a category token before use, so
 * nothing from the cookie can reach the header text.
 */
@Component
public class PluginCspHeaderWriter implements HeaderWriter {

    private static final String HEADER = "Content-Security-Policy";

    /** Directives a declared third-party host is added to; the rest stay {@code 'self'}. */
    private static final String[] WIDENED = {"script-src", "frame-src", "connect-src"};

    private final ConsentService consent;
    private final ExternalMediaHostRegistry mediaHosts;
    private final boolean strictMediaSources;

    public PluginCspHeaderWriter(ConsentService consent, ExternalMediaHostRegistry mediaHosts,
            @org.springframework.beans.factory.annotation.Value(
                    "${mosaicast.security.strict-media-sources:false}") boolean strictMediaSources) {
        this.consent = consent;
        this.mediaHosts = mediaHosts;
        this.strictMediaSources = strictMediaSources;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader(HEADER)) {
            return;
        }
        Set<String> declared = consent.allowedSources(ConsentCookie.grantedIn(request));
        response.setHeader(HEADER, policy(declared, strictMediaSources ? mediaHosts.origins() : null));
        if (consent.hasOptionalSources()) {
            // The policy now differs between visitors, so any shared cache has to key on the cookie or it
            // will hand one visitor's allow-list to another. Only added when something is actually gated:
            // on a site with no optional services the header is the same for everyone, and `Vary: Cookie`
            // would cost cacheability for nothing.
            response.addHeader(HttpHeaders.VARY, HttpHeaders.COOKIE);
        }
    }

    /**
     * The policy string for a set of declared hosts (package-visible so it can be asserted directly).
     *
     * @param declaredSources the plugin-declared third-party hosts this visitor has consented to
     * @param mediaSources    the origins to narrow {@code img-src}/{@code media-src} to, or {@code null} to
     *                        keep the blanket {@code https:} — see {@link ExternalMediaHostRegistry}
     */
    static String policy(Set<String> declaredSources, Set<String> mediaSources) {
        String extra = declaredSources.stream()
                .map(PluginCspHeaderWriter::sanitize)
                .filter(source -> !source.isBlank())
                .collect(Collectors.joining(" "));
        String suffix = extra.isBlank() ? "" : " " + extra;

        // `style-src 'unsafe-inline'` is deliberate, and it is the weakest line in this policy — so it gets
        // an explicit reason rather than an inherited one.
        //
        // Plugin UIs are Web Components that style themselves with a `<style>` block inside their shadow
        // root, which is the encapsulation model §12.3 is built on (the `--mc-*` tokens inherit across the
        // boundary so a plugin re-themes with the site). Shadow-DOM styles are governed by the *document's*
        // CSP, so dropping this breaks every plugin that styles itself — the whole plugin UI contract, not an
        // edge of it. Nonces do not rescue it either: a plugin's bundle constructs its shadow root at runtime
        // and has no way to carry a per-response nonce.
        //
        // What it costs, stated plainly: inline CSS can be injected wherever attacker-influenced HTML is
        // rendered. That is show notes and feed descriptions, whose author is whoever runs the podcast host.
        // The attack this enables — attribute-selector exfiltration of rendered values, a full-page
        // click-jacking overlay — is closed on the *sanitizer* side instead: the shell strips `<style>` and
        // `style` from feed HTML before it is ever inserted (see `sanitize.ts`), so there is no path from
        // feed content to a stylesheet. Script execution was never available here; `script-src` stays strict.
        // `img-src`/`media-src`: blanket `https:` by default, because artwork and audio come from whatever
        // host the podcaster's feed points at. That makes any https image a working one-way beacon, past
        // `connect-src 'self'` and past consent — so strict mode replaces the blanket with the origins the
        // site's own content actually uses, plus whatever this visitor consented to. `data:` stays on
        // `img-src` either way: the shell inlines small assets, and a data URI reaches nobody.
        // The consented plugin hosts join these only in strict mode; under the blanket they are already
        // covered, and listing them would suggest the directive means more than it does.
        String media = mediaSources == null ? " https:" : mediaSources.stream()
                .map(PluginCspHeaderWriter::sanitize)
                .filter(source -> !source.isBlank())
                .map(source -> " " + source)
                .collect(Collectors.joining()) + suffix;
        StringBuilder policy = new StringBuilder("default-src 'self'; ")
                .append("img-src 'self' data:").append(media).append("; ")
                .append("media-src 'self'").append(media).append("; ")
                .append("style-src 'self' 'unsafe-inline'; ");
        for (String directive : WIDENED) {
            policy.append(directive).append(" 'self'").append(suffix).append("; ");
        }
        return policy.append("object-src 'none'; base-uri 'self'; frame-ancestors 'none'").toString();
    }

    /**
     * A manifest value ends up in a response header, so anything that could break out of the directive —
     * separators, whitespace, control characters — disqualifies the entry rather than being escaped.
     */
    private static String sanitize(String source) {
        if (source == null) {
            return "";
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(c -> c <= ' ' || c == ';' || c == ',')) {
            return "";
        }
        return trimmed;
    }
}
