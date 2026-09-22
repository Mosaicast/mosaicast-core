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
        SecretBox box = new SecretBox(key(), null);

        String sealed = box.seal("sk-live-1234");

        assertThat(box.encrypting()).isTrue();
        assertThat(sealed).startsWith("v1:").doesNotContain("sk-live-1234");
        assertThat(box.open(sealed)).isEqualTo("sk-live-1234");
    }

    @Test
    void sealingTwiceProducesDifferentCiphertext() {
        // A fresh nonce per value: identical ciphertext would tell anyone with the table which two providers
        // share a credential.
        SecretBox box = new SecretBox(key(), null);

        assertThat(box.seal("same")).isNotEqualTo(box.seal("same"));
    }

    @Test
    void withoutAKeyValuesPassThroughRatherThanFailingTheBoot() {
        // Refusing to start would take a site down over a feature it may not use. The operator gets a WARN,
        // an admin-page badge, and the decision.
        SecretBox box = new SecretBox("", null);

        assertThat(box.encrypting()).isFalse();
        assertThat(box.seal("sk-live-1234")).isEqualTo("sk-live-1234");
        assertThat(box.open("sk-live-1234")).isEqualTo("sk-live-1234");
    }

    @Test
    void aPlaintextValueSurvivesAKeyBeingAddedLater() {
        // What an instance that ran without a key already wrote. An operator who adds one should not find
        // every provider suddenly broken.
        assertThat(new SecretBox(key(), null).open("sk-live-1234")).isEqualTo("sk-live-1234");
    }

    @Test
    void theWrongKeyFailsWithoutSayingWhichWayItFailed() {
        SecretBox sealed = new SecretBox(key(), null);
        String value = sealed.seal("sk-live-1234");

        assertThatThrownBy(() -> new SecretBox(otherKey(), null).open(value))
                .isInstanceOf(IllegalStateException.class)
                // "wrong key" vs "corrupt" reaches an admin page; telling them apart there is an oracle.
                .hasMessage("A stored credential could not be decrypted");

        assertThatThrownBy(() -> new SecretBox("", null).open(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOSAICAST_ENCRYPTION_KEY is not set");
    }

    @Test
    void aKeyOfTheWrongLengthIsFatal() {
        // A typo here would otherwise silently downgrade an instance the operator meant to protect.
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16]), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new SecretBox("not base64!!", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void nullPassesThrough() {
        assertThat(new SecretBox(key(), null).seal(null)).isNull();
        assertThat(new SecretBox(key(), null).open(null)).isNull();
    }

    @Test
    void aValueSealedWithThePreviousKeyStillOpensDuringARotation() {
        // Changing the key used to make every stored value unreadable with no way back: no key id, no
        // second key, no re-seal path (core#186). Reading with the previous key is what makes a rotation a
        // procedure rather than a data loss — set it, restart, re-save each credential, drop it again.
        String oldKey = key();
        String newKey = otherKey();
        String sealed = new SecretBox(oldKey, null).seal("sk-live-1234");

        assertThat(new SecretBox(newKey, oldKey).open(sealed)).isEqualTo("sk-live-1234");
        // And the new key is the one that seals, so a re-save moves the value forward.
        String resealed = new SecretBox(newKey, oldKey).seal("sk-live-1234");
        assertThat(new SecretBox(newKey, null).open(resealed)).isEqualTo("sk-live-1234");
    }

    @Test
    void aPreviousKeyWithoutACurrentOneIsRefused() {
        // It only decrypts. Without a current key there is nothing to rotate onto, and starting anyway
        // would quietly store the next credential in plain text.
        assertThatThrownBy(() -> new SecretBox("", key()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOSAICAST_ENCRYPTION_KEY");
    }
}
