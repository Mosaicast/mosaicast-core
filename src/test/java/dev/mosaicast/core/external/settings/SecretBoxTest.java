// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Encryption at rest for admin-typed credentials (ARCHITECTURE §13), and what happens without a key. */
class SecretBoxTest {

    private static String key() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String otherKey() {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) 7);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void roundTripsWithAKey() {
        SecretBox box = new SecretBox(key());

        String sealed = box.seal("sk-live-1234");

        assertThat(box.encrypting()).isTrue();
        assertThat(sealed).startsWith("v1:").doesNotContain("sk-live-1234");
        assertThat(box.open(sealed)).isEqualTo("sk-live-1234");
    }

    @Test
    void sealingTwiceProducesDifferentCiphertext() {
        // A fresh nonce per value: identical ciphertext would tell anyone with the table which two providers
        // share a credential.
        SecretBox box = new SecretBox(key());

        assertThat(box.seal("same")).isNotEqualTo(box.seal("same"));
    }

    @Test
    void withoutAKeyValuesPassThroughRatherThanFailingTheBoot() {
        // Refusing to start would take a site down over a feature it may not use. The operator gets a WARN,
        // an admin-page badge, and the decision.
        SecretBox box = new SecretBox("");

        assertThat(box.encrypting()).isFalse();
        assertThat(box.seal("sk-live-1234")).isEqualTo("sk-live-1234");
        assertThat(box.open("sk-live-1234")).isEqualTo("sk-live-1234");
    }

    @Test
    void aPlaintextValueSurvivesAKeyBeingAddedLater() {
        // What an instance that ran without a key already wrote. An operator who adds one should not find
        // every provider suddenly broken.
        assertThat(new SecretBox(key()).open("sk-live-1234")).isEqualTo("sk-live-1234");
    }

    @Test
    void theWrongKeyFailsWithoutSayingWhichWayItFailed() {
        SecretBox sealed = new SecretBox(key());
        String value = sealed.seal("sk-live-1234");

        assertThatThrownBy(() -> new SecretBox(otherKey()).open(value))
                .isInstanceOf(IllegalStateException.class)
                // "wrong key" vs "corrupt" reaches an admin page; telling them apart there is an oracle.
                .hasMessage("A stored credential could not be decrypted");

        assertThatThrownBy(() -> new SecretBox("").open(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOSAICAST_ENCRYPTION_KEY is not set");
    }

    @Test
    void aKeyOfTheWrongLengthIsFatal() {
        // A typo here would otherwise silently downgrade an instance the operator meant to protect.
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new SecretBox("not base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void nullPassesThrough() {
        assertThat(new SecretBox(key()).seal(null)).isNull();
        assertThat(new SecretBox(key()).open(null)).isNull();
    }
}
