// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.feed.OutboundTargetPolicy;
import org.junit.jupiter.api.Test;

/**
 * Where a provider may connect (ARCHITECTURE §12.7/§13).
 *
 * <p>The point of this class is that it is <em>narrower</em> than the feed policy, not a way around it: an
 * operator gets one named service on one port, not the whole private network.
 */
class ExternalTargetPolicyTest {

    private static ExternalTargetPolicy policy(String allowed) {
        return new ExternalTargetPolicy(new OutboundTargetPolicy(false, false), allowed);
    }

    @Test
    void aPrivateAddressIsRefusedByDefault() {
        assertThatThrownBy(() -> policy("").validate("http://localhost:5000/translate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        assertThatThrownBy(() -> policy("").validate("http://192.168.1.20:5000"))
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void anExactlyAllowListedOriginIsPermitted() {
        assertThatCode(() -> policy("http://localhost:5000").validate("http://localhost:5000/translate"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowListingOneOriginDoesNotOpenTheHostOrTheNetwork() {
        // The whole reason this is not `allow-private-targets`: granting the translator must not grant
        // Redis on the same box, or the cloud metadata service.
        ExternalTargetPolicy allowed = policy("http://localhost:5000");

        assertThatThrownBy(() -> allowed.validate("http://localhost:6379"))
                .as("a different port on the same host")
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        assertThatThrownBy(() -> allowed.validate("https://localhost:5000"))
                .as("a different scheme")
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        assertThatThrownBy(() -> allowed.validate("http://169.254.169.254/latest/meta-data/"))
                .as("the cloud metadata service")
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        assertThatThrownBy(() -> allowed.validate("http://127.0.0.1:5000"))
                .as("the same service by a different name — the allow-list is textual, by design")
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void theDefaultPortIsUnderstood() {
        // http://host and http://host:80 are the same origin; an operator should not have to guess which
        // spelling the allow-list wants.
        assertThatCode(() -> policy("http://libretranslate.internal").validate("http://libretranslate.internal:80/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy("https://mt.internal:443").validate("https://mt.internal/x"))
                .doesNotThrowAnyException();
    }

    @Test
    void everyRefusalProducesTheIdenticalMessage() {
        // Distinguishable errors are the attack: this URL is admin-supplied and the outcome is rendered
        // back to them, so a settings form that reported *why* would be an internal port scanner.
        ExternalTargetPolicy allowed = policy("http://localhost:5000");
        for (String url : new String[] {
                "http://localhost:6379", "ftp://localhost:5000", "http://user:pw@localhost:5000",
                "not a url at all", "", "http://10.0.0.5:5000",
        }) {
            assertThatThrownBy(() -> allowed.validate(url))
                    .as("refusal for %s", url)
                    .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        }
    }

    @Test
    void credentialsInTheAuthorityAreRefusedEvenForAnAllowedOrigin() {
        // A URL that reads as one host and resolves as another; never needed here.
        assertThatThrownBy(() -> policy("http://localhost:5000").validate("http://a:b@localhost:5000"))
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void anUnparseableAllowListFailsTheBoot() {
        // Silently dropping a typo'd entry means the operator's translator mysteriously does not work and
        // the fix is invisible. Naming it at boot costs one restart.
        assertThatThrownBy(() -> policy("not-an-origin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheme://host:port");
        assertThatThrownBy(() -> policy("http://localhost:5000/with/a/path"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void severalOriginsMayBeAllowed() {
        ExternalTargetPolicy allowed = policy("http://localhost:5000, http://mt.internal:8080");

        assertThatCode(() -> allowed.validate("http://localhost:5000/translate")).doesNotThrowAnyException();
        assertThatCode(() -> allowed.validate("http://mt.internal:8080/translate")).doesNotThrowAnyException();
        assertThat(ExternalTargetPolicy.BLOCKED_MESSAGE).contains("MOSAICAST_EXTERNAL_ALLOWED_PRIVATE_ORIGINS");
    }
}
