// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.http;

import dev.mosaicast.core.web.CodedBadRequest;
import dev.mosaicast.core.feed.OutboundTargetPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where an external-service provider is allowed to connect (ARCHITECTURE §12.7/§13).
 *
 * <p>A self-hosted translator lives at {@code http://libretranslate:5000} or {@code http://192.168.1.20:5000}
 * — exactly the addresses {@link OutboundTargetPolicy} exists to refuse. The existing escape hatch is
 * <strong>the wrong lever</strong>: {@code mosaicast.feed.allow-private-targets} disables the SSRF filter on
 * the <em>feed</em> path, the podcaster-writable surface whose preview endpoint reads the response back to
 * the caller — the surface that class's javadoc records an audit turning into a working internal port
 * scanner. An operator flipping it so their translator works would open that as a side effect. That coupling
 * must not exist.
 *
 * <p>So: this wraps the feed policy <strong>unchanged</strong> and adds a narrower control beside it — an
 * allow-list of exact origins, {@code mosaicast.external.allowed-private-origins}. Scheme, host and port all
 * match literally, no wildcards. {@code http://libretranslate:5000} permits one service on one port; it does
 * not permit {@code http://127.0.0.1:6379} or the cloud metadata endpoint. That is a decision an operator
 * makes per service, not a switch that opens everything, which is why it needs no second confirmation flag
 * the way the feed one does.
 *
 * <p>A public address still goes through {@link OutboundTargetPolicy} verbatim, so a hosted provider gets the
 * full check — userinfo rejection, IPv4-mapped-IPv6 unwrapping and all — for free.
 *
 * <p><strong>Validated twice</strong>, at settings-save time so the admin sees the refusal in the form, and
 * again at call time because DNS moves. The residual DNS-rebinding window is inherited and real: an operator
 * who needs it fully closed wants an egress proxy, which is the same conclusion the feed policy reaches.
 */
@Component
public class ExternalTargetPolicy {

    private static final Logger log = LoggerFactory.getLogger(ExternalTargetPolicy.class);

    /**
     * One message for every refusal.
     *
     * <p>Same reasoning as {@link OutboundTargetPolicy#BLOCKED_MESSAGE}, and it matters more here: this URL
     * is admin-supplied and the outcome is rendered back to them, so distinguishable failures would make the
     * settings form a probe for whatever is listening on the internal network.
     */
    /** The code for {@link #BLOCKED_MESSAGE}, which the shell translates (core#192). */
    public static final String BLOCKED_CODE = "external.url.blocked";

    public static final String BLOCKED_MESSAGE =
            "That address cannot be used. It must be publicly reachable, or explicitly allow-listed via "
                    + "MOSAICAST_EXTERNAL_ALLOWED_PRIVATE_ORIGINS.";

    private final OutboundTargetPolicy publicPolicy;
    private final Set<String> allowedPrivateOrigins;

    public ExternalTargetPolicy(
            OutboundTargetPolicy publicPolicy,
            @Value("${mosaicast.external.allowed-private-origins:"
                    + "${MOSAICAST_EXTERNAL_ALLOWED_PRIVATE_ORIGINS:}}") String allowed) {
        this.publicPolicy = publicPolicy;
        this.allowedPrivateOrigins = parse(allowed);
        if (!allowedPrivateOrigins.isEmpty()) {
            // Loud once, naming each one: an allow-list nobody remembers granting is the same problem as a
            // switch nobody remembers flipping.
            log.warn("External services may reach these non-public origins: {}",
                    String.join(", ", allowedPrivateOrigins));
        }
    }

    /**
     * Parses the configured origins, refusing to start on one it cannot interpret.
     *
     * <p>Fatal rather than skipped: an origin silently dropped for a typo means the operator's translator
     * mysteriously does not work, and the fix is invisible. A boot failure names the entry.
     */
    private static Set<String> parse(String configured) {
        Set<String> origins = new LinkedHashSet<>();
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                URI uri = new URI(trimmed);
                if (uri.getScheme() == null || uri.getHost() == null || uri.getPath() != null
                        && !uri.getPath().isEmpty()) {
                    throw new URISyntaxException(trimmed, "not a bare origin");
                }
                origins.add(origin(uri));
            } catch (URISyntaxException problem) {
                throw new IllegalStateException(
                        "mosaicast.external.allowed-private-origins: '%s' is not a scheme://host:port origin"
                                .formatted(trimmed), problem);
            }
        }
        return Set.copyOf(origins);
    }

    /**
     * Validates a provider's target URL.
     *
     * @param url the admin-supplied address
     * @return the parsed URI
     * @throws IllegalArgumentException with {@link #BLOCKED_MESSAGE} for every refusal, whatever the reason
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new CodedBadRequest(BLOCKED_CODE, BLOCKED_MESSAGE);
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException problem) {
            throw new CodedBadRequest(BLOCKED_CODE, BLOCKED_MESSAGE);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new CodedBadRequest(BLOCKED_CODE, BLOCKED_MESSAGE);
        }
        // Credentials in the authority make a URL read as one host and resolve as another. Never needed here.
        if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new CodedBadRequest(BLOCKED_CODE, BLOCKED_MESSAGE);
        }
        if (allowedPrivateOrigins.contains(origin(uri))) {
            return uri;
        }
        try {
            publicPolicy.validate(uri);
        } catch (IllegalArgumentException refused) {
            // The real reason goes to the log; the caller gets one opaque sentence.
            log.warn("Refused outbound external-service request to '{}'", uri.getHost());
            throw new CodedBadRequest(BLOCKED_CODE, BLOCKED_MESSAGE);
        }
        return uri;
    }

    /** {@code scheme://host:port}, lower-cased, with the scheme's default port made explicit. */
    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
        return "%s://%s:%d".formatted(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
    }
}
