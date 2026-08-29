// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts admin-typed credentials at rest, when the operator gave us a key (ARCHITECTURE §13).
 *
 * <p>{@code MOSAICAST_ENCRYPTION_KEY} has been reserved in {@code .env.example} since v1 and wired to nothing.
 * A {@link SettingsFieldType#SECRET} is the first value that needs it, so this is where it starts being real.
 *
 * <p><strong>Absent key means plaintext, loudly.</strong> Refusing to start would take a site down over a
 * feature it may not use; silently storing a credential in the clear would be worse than either. So: one WARN
 * at startup, a flag the admin page renders next to every stored secret, and it keeps working. The operator
 * gets to decide, having been told.
 *
 * <p>AES-GCM with a random 96-bit nonce per value, stored as {@code v1:<base64(nonce||ciphertext||tag)>}. The
 * version prefix is there so a future scheme can be told apart from this one without guessing, and so a value
 * written before the key existed is recognisable as plaintext rather than mistaken for corrupt ciphertext.
 *
 * <p><strong>What this is not.</strong> A key sitting in the same compose file as the database password is
 * not protection from someone who has the box; it is protection from a backup, a snapshot or a support
 * bundle leaving the box. That is a real threat and a limited one, and the documentation says so rather than
 * implying more.
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

    private static final String PREFIX = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${mosaicast.encryption-key:${MOSAICAST_ENCRYPTION_KEY:}}") String configured) {
        this.key = parse(configured);
        if (key == null) {
            log.warn("MOSAICAST_ENCRYPTION_KEY is not set — admin-entered service credentials will be stored "
                    + "in plain text, and will appear in database backups. Prefer environment-supplied "
                    + "credentials, or set a 32-byte base64 key.");
        }
    }

    private static SecretKeySpec parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(configured.trim());
            if (raw.length != KEY_BYTES) {
                // Loud and fatal: a key of the wrong length is a typo, and starting without encryption
                // because of one would silently downgrade an instance the operator meant to protect.
                throw new IllegalStateException(
                        "MOSAICAST_ENCRYPTION_KEY must decode to %d bytes, got %d".formatted(KEY_BYTES, raw.length));
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("MOSAICAST_ENCRYPTION_KEY must be base64", notBase64);
        }
    }

    /** Whether values are actually encrypted. Surfaced in the admin UI, so the operator is not guessing. */
    public boolean encrypting() {
        return key != null;
    }

    /**
     * Wraps a credential for storage.
     *
     * @param plaintext the value; {@code null} passes through
     * @return the stored form — ciphertext when a key is configured, the value unchanged when not
     */
    public String seal(String plaintext) {
        if (plaintext == null || key == null) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException problem) {
            throw new IllegalStateException("Could not encrypt a stored credential", problem);
        }
    }

    /**
     * Unwraps a stored credential.
     *
     * <p>A value without the version prefix is returned as-is: that is what an instance that ran without a
     * key wrote, and an operator who adds one later should not find every provider suddenly broken.
     *
     * @param stored the stored form
     * @return the credential
     * @throws IllegalStateException if the value is sealed and this instance has no key, or the wrong one
     */
    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "A stored credential is encrypted but MOSAICAST_ENCRYPTION_KEY is not set");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException problem) {
            // Deliberately not "wrong key" vs "corrupt": the message reaches an admin page, and telling a
            // caller which of the two it is turns this into an oracle. The distinction is in the log.
            log.warn("A stored credential could not be decrypted", problem);
            throw new IllegalStateException("A stored credential could not be decrypted");
        }
    }
}
