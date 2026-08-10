// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether the server may make an outbound HTTP request to an operator-supplied URL.
 *
 * <p>A feed URL is attacker-influenced input that the <em>server</em> dereferences, which is the definition of
 * SSRF. Two things make it worse than the usual shape here: {@code /api/admin/feeds/preview} returns the
 * fetched channel title and the first ten item titles to the caller, so anything internal that speaks
 * RSS/Atom is read out loud rather than merely reached; and the endpoint is open to PODCASTER, not only ADMIN
 * (§8.5). "Only trusted roles can add feeds" is a mitigation, not an answer — it puts the whole internal
 * network one compromised podcaster account behind a text field.
 *
 * <p>So: the host is resolved and <strong>every</strong> address it answers with must be publicly routable.
 * Rejecting on any single bad address rather than all of them is deliberate — a name that resolves to one
 * public and one loopback address is a rebinding attempt, not a multi-homed server.
 *
 * <p><strong>What this does not close.</strong> Resolution here and connection later are two separate lookups,
 * so a name whose record changes in between can still land somewhere private (classic DNS rebinding). Closing
 * that needs the connection pinned to the address that was checked, which the JDK's {@code HttpClient} does
 * not expose. The redirect chain is re-checked per hop ({@link RssFeedSource}), which removes the easy version
 * of the bypass; the racy version remains, and an operator who needs it fully closed wants an egress proxy.
 *
 * <p><strong>{@code mosaicast.feed.allow-private-targets}</strong> turns the check off. It exists because a
 * self-hosted install legitimately may pull a feed from another host on its own LAN, and because the
 * integration tests serve fixtures from loopback. It defaults to {@code false} and should stay there on
 * anything reachable from a network you do not control.
 *
 * <p>Because it disables the whole control, it needs a <strong>second</strong> flag,
 * {@code mosaicast.feed.allow-private-targets-confirmed}, and the app refuses to start without it. A single
 * environment variable is too easy to set while chasing something else — copied from a colleague's compose
 * file, left over from a debugging session — and the failure is silent: everything keeps working, and the
 * only difference is that the SSRF filter is gone. A boot failure is loud, happens once, and cannot be
 * mistaken for normal operation. There is deliberately no profile exemption: "it is fine in dev" is exactly
 * the reasoning that ends with a dev instance on a network somebody can reach.
 */
@Component
public class OutboundTargetPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutboundTargetPolicy.class);

    private final boolean allowPrivateTargets;

    public OutboundTargetPolicy(
            @Value("${mosaicast.feed.allow-private-targets:false}") boolean allowPrivateTargets,
            @Value("${mosaicast.feed.allow-private-targets-confirmed:false}") boolean confirmed) {
        if (allowPrivateTargets && !confirmed) {
            throw new IllegalStateException(
                    "mosaicast.feed.allow-private-targets is ON, which disables the SSRF egress filter "
                            + "entirely: any feed URL may then point at loopback, link-local (including the "
                            + "cloud metadata service) or private addresses, and /api/admin/feeds/preview "
                            + "reads the response back to the caller. If that is genuinely what this "
                            + "install needs — a feed on its own LAN — also set "
                            + "mosaicast.feed.allow-private-targets-confirmed=true. Refusing to start "
                            + "rather than run unprotected on a switch somebody may have set by accident.");
        }
        this.allowPrivateTargets = allowPrivateTargets;
        if (allowPrivateTargets) {
            log.warn("mosaicast.feed.allow-private-targets is ON — feed URLs may point at loopback, "
                    + "link-local and private addresses. Do not run this way in production.");
        }
    }

    /**
     * Validates a feed URL and returns it parsed.
     *
     * @throws IllegalArgumentException if the URL is malformed, not http(s), or resolves anywhere the server
     *     must not reach. The message is deliberately the same for every rejection reason — see
     *     {@link #BLOCKED_MESSAGE}.
     */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Feed URL must be an http(s) URL");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Feed URL must be an http(s) URL");
        }
        return validate(uri);
    }

    /** As {@link #validate(String)}, for a URI already in hand — the per-hop redirect check. */
    public URI validate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Feed URL must be an http(s) URL");
        }
        // Credentials in the authority are never needed for a public feed and are a reliable way to make a URL
        // read as one host while resolving as another.
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(BLOCKED_MESSAGE);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(BLOCKED_MESSAGE);
        }
        if (allowPrivateTargets) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(BLOCKED_MESSAGE);
        }
        for (InetAddress address : addresses) {
            if (!isPubliclyRoutable(address)) {
                log.warn("Refused outbound feed request to '{}': resolves to non-public address", host);
                throw new IllegalArgumentException(BLOCKED_MESSAGE);
            }
        }
        return uri;
    }

    /**
     * One message for every rejection.
     *
     * <p>Distinguishable errors are the whole attack. The audit demonstrated a working internal port scanner
     * built from nothing but the difference between "reachable but not RSS", "connection refused" and
     * "HTTP 403" — all three surfaced verbatim in the 400 body. A single opaque message costs an operator who
     * typo'd a URL very little and costs a scanner everything.
     */
    public static final String BLOCKED_MESSAGE =
            "Feed URL could not be used. It must be a publicly reachable http(s) address.";

    /**
     * Whether an address is one the internet can route to — i.e. not somewhere only this host or this network
     * can see.
     *
     * <p>{@code isSiteLocalAddress} covers 10/8, 172.16/12 and 192.168/16 but, for IPv6, only the deprecated
     * {@code fec0::/10}; unique-local {@code fc00::/7} is today's equivalent and is checked separately. The
     * remaining literals are ranges the JDK has no predicate for and that are just as unroutable.
     */
    static boolean isPubliclyRoutable(InetAddress address) {
        if (!isRoutableItself(address)) {
            return false;
        }
        // …and again as the IPv4 address it wraps, if it wraps one. Checking the wrapper first matters:
        // `::1` is shaped like the IPv4-compatible form `::0.0.0.1`, so unwrapping before testing turns the
        // IPv6 loopback into an unremarkable-looking 0.0.0.1 and waves it through.
        InetAddress unwrapped = unwrapIpv4(address);
        return unwrapped == address || isRoutableItself(unwrapped);
    }

    private static boolean isRoutableItself(InetAddress target) {
        if (target.isAnyLocalAddress()          // 0.0.0.0, ::
                || target.isLoopbackAddress()   // 127/8, ::1
                || target.isLinkLocalAddress()  // 169.254/16 (incl. cloud metadata), fe80::/10
                || target.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16, fec0::/10
                || target.isMulticastAddress()) {
            return false;
        }
        if (target instanceof Inet4Address) {
            byte[] b = target.getAddress();
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 100.64/10 carrier-grade NAT — routable-looking, reaches the provider's private space.
            if (first == 100 && second >= 64 && second <= 127) {
                return false;
            }
            // 192.0.0.0/24 IETF protocol assignments, 198.18/15 benchmarking.
            if (first == 192 && second == 0 && (b[2] & 0xFF) == 0) {
                return false;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;
            }
            // 240/4 reserved, which includes the 255.255.255.255 broadcast address.
            return first < 240;
        }
        if (target instanceof Inet6Address) {
            // fc00::/7 unique local. The JDK has no predicate for it.
            return (target.getAddress()[0] & 0xFE) != 0xFC;
        }
        return true;
    }

    /**
     * Unwraps an IPv4-mapped or IPv4-compatible IPv6 address, so {@code ::ffff:127.0.0.1} is judged as the
     * loopback address it actually is rather than as an unremarkable IPv6 address.
     */
    private static InetAddress unwrapIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address v6)) {
            return address;
        }
        byte[] bytes = v6.getAddress();
        boolean prefixIsZero = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                prefixIsZero = false;
                break;
            }
        }
        if (!prefixIsZero) {
            return address;
        }
        boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
        boolean compatible = bytes[10] == 0 && bytes[11] == 0;
        if (!mapped && !compatible) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            return address;
        }
    }
}
