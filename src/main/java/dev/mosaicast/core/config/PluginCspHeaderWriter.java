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

    public PluginCspHeaderWriter(ConsentService consent) {
        this.consent = consent;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader(HEADER)) {
            return;
        }
        response.setHeader(HEADER, policy(consent.allowedSources(ConsentCookie.grantedIn(request))));
        if (consent.hasOptionalSources()) {
            // The policy now differs between visitors, so any shared cache has to key on the cookie or it
            // will hand one visitor's allow-list to another. Only added when something is actually gated:
            // on a site with no optional services the header is the same for everyone, and `Vary: Cookie`
            // would cost cacheability for nothing.
            response.addHeader(HttpHeaders.VARY, HttpHeaders.COOKIE);
        }
    }

    /** The policy string for a set of declared hosts (package-visible so it can be asserted directly). */
    static String policy(Set<String> declaredSources) {
        String extra = declaredSources.stream()
                .map(PluginCspHeaderWriter::sanitize)
                .filter(source -> !source.isBlank())
                .collect(Collectors.joining(" "));
        String suffix = extra.isBlank() ? "" : " " + extra;

        StringBuilder policy = new StringBuilder("default-src 'self'; ")
                .append("img-src 'self' data: https:; ")
                .append("media-src 'self' https:; ")
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
