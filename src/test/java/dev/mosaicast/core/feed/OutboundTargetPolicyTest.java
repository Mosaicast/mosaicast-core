// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF guard on operator-supplied feed URLs (see {@link OutboundTargetPolicy}).
 *
 * <p>Address classification is tested directly rather than through {@code validate}, because resolving a
 * hostname in a unit test means either depending on DNS or on {@code /etc/hosts} — neither of which belongs in
 * a build. The literals below skip resolution entirely: {@code InetAddress.getAllByName("10.0.0.1")} parses
 * the literal without a lookup, so those cases are hermetic.
 */
class OutboundTargetPolicyTest {

    private final OutboundTargetPolicy policy = new OutboundTargetPolicy(false, false);

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/feed.xml",              // loopback
        "http://127.1.2.3/feed.xml",              // the rest of 127/8, which a naive equality check misses
        "http://[::1]/feed.xml",                  // IPv6 loopback
        "http://169.254.169.254/latest/meta-data/", // cloud instance metadata — credential theft
        "http://[fe80::1]/feed.xml",              // IPv6 link-local
        "http://10.0.0.5:8080/feed.xml",          // RFC-1918
        "http://172.16.0.1/feed.xml",
        "http://192.168.1.1/feed.xml",
        "http://100.64.0.1/feed.xml",             // carrier-grade NAT
        "http://[fc00::1]/feed.xml",              // IPv6 unique-local, which isSiteLocalAddress misses
        "http://[fd12:3456::1]/feed.xml",
        "http://0.0.0.0/feed.xml",
        "http://[::ffff:127.0.0.1]/feed.xml",     // IPv4-mapped loopback wearing an IPv6 costume
        "http://198.18.0.1/feed.xml",             // benchmarking range
        "http://240.0.0.1/feed.xml",              // reserved
        "http://255.255.255.255/feed.xml",        // broadcast
    })
    void refusesTargetsTheInternetCannotRoute(String url) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(OutboundTargetPolicy.BLOCKED_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "gopher://127.0.0.1:70/",
        "dict://127.0.0.1:2628/",
        "jar:http://example.com/a.jar!/",
        "ftp://example.com/feed.xml",
    })
    void refusesEverySchemeButHttpAndHttps(String url) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Feed URL must be an http(s) URL");
    }

    @Test
    void refusesCredentialsInTheAuthority() {
        // `http://good.example@127.0.0.1/` reads as one host and resolves as another.
        assertThatThrownBy(() -> policy.validate("http://good.example@127.0.0.1/feed.xml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(OutboundTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void refusesGarbage() {
        assertThatThrownBy(() -> policy.validate("not a url at all"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate((String) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRejectionReadsTheSame() {
        // The point of the single message: the audit built a working internal port scanner purely out of the
        // difference between "not RSS", "connection refused" and "HTTP 403" leaking into the 400 body.
        String blockedHost = catchMessage("http://127.0.0.1/feed.xml");
        String blockedRange = catchMessage("http://10.1.2.3/feed.xml");
        String blockedMetadata = catchMessage("http://169.254.169.254/");
        assertThat(blockedHost).isEqualTo(blockedRange).isEqualTo(blockedMetadata);
    }

    @Test
    void allowsAnOrdinaryPublicAddress() {
        assertThatCode(() -> policy.validate("https://93.184.216.34/feed.xml")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("http://8.8.8.8/feed.xml")).doesNotThrowAnyException();
    }

    @Test
    void theEscapeHatchNeedsBothKeys() {
        // One environment variable is too easy to set while chasing something else and leave behind, and the
        // failure is silent: everything keeps working, only the SSRF filter is gone. Refusing to start is
        // loud, happens once, and cannot be mistaken for normal operation.
        assertThatThrownBy(() -> new OutboundTargetPolicy(true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-private-targets-confirmed");

        // The confirmation on its own is inert — it confirms a switch, it does not flip one.
        assertThatCode(() -> new OutboundTargetPolicy(false, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new OutboundTargetPolicy(false, true).validate("http://127.0.0.1/f.xml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEscapeHatchReallyOpensIt() {
        // A self-hosted install may legitimately pull a feed from another host on its own LAN.
        OutboundTargetPolicy permissive = new OutboundTargetPolicy(true, true);
        assertThatCode(() -> permissive.validate("http://127.0.0.1:8077/feed.xml")).doesNotThrowAnyException();
        // The scheme check is not part of the hatch.
        assertThatThrownBy(() -> permissive.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesAddressesDirectly() throws UnknownHostException {
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("100.127.255.255"))).isFalse();
        // 100.128.0.0 is outside 100.64/10 and is ordinary public space — the mask has to be a /10, not a /8.
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("100.128.0.1"))).isTrue();
        assertThat(OutboundTargetPolicy.isPubliclyRoutable(InetAddress.getByName("239.255.255.250"))).isFalse();
    }

    private String catchMessage(String url) {
        try {
            policy.validate(url);
            throw new AssertionError("expected " + url + " to be refused");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
