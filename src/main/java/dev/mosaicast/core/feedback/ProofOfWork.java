// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The proof of work an anonymous feedback sender solves (ARCHITECTURE §20.4) — a self-hosted stand-in for a
 * captcha that needs no third party, no cookie and no puzzle.
 *
 * <p>A challenge is {@code <payload>.<signature>}: the payload carries when it was issued, a random nonce and the
 * difficulty, and the HMAC is keyed by a secret made at boot and never written down. The browser finds a counter
 * such that SHA-256 of {@code <challenge>:<counter>} starts with that many zero bits — about a second on a phone
 * at the default, and that second again for every message a bulk sender wants. Each challenge is accepted once:
 * spent ones are remembered until they would have expired anyway. A restart forgets the secret, so the only cost
 * is that challenges in flight stop verifying.
 */
@Component
public class ProofOfWork {

    /** Why a solution was refused. The sender is told only that the check failed, never which part. */
    public enum Failure { MALFORMED, FORGED, EXPIRED, TOO_FAST, WRONG, SPENT }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final FeedbackProperties properties;
    private final Clock clock;
    private final byte[] key = new byte[32];
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Boolean> spent;

    public ProofOfWork(FeedbackProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        random.nextBytes(key);
        this.spent = Caffeine.newBuilder()
                .expireAfterWrite(properties.challengeTtl())
                .maximumSize(100_000)
                .build();
    }

    /** A fresh challenge, and the difficulty the browser has to meet. */
    public Challenge issue() {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        String payload = clock.instant().toEpochMilli() + "." + B64.encodeToString(nonce) + "." + properties.powBits();
        String encoded = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Challenge(encoded + "." + sign(encoded), properties.powBits());
    }

    /**
     * Checks a solved challenge, and spends it when it passes.
     *
     * @return null when it holds, otherwise why not
     */
    public Failure verify(String challenge, String counter) {
        if (challenge == null || counter == null || counter.length() > 20 || !counter.chars().allMatch(Character::isDigit)) {
            return Failure.MALFORMED;
        }
        int dot = challenge.lastIndexOf('.');
        if (dot <= 0) {
            return Failure.MALFORMED;
        }
        String encoded = challenge.substring(0, dot);
        byte[] given = challenge.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, sign(encoded).getBytes(StandardCharsets.US_ASCII))) {
            return Failure.FORGED;
        }
        String[] parts;
        try {
            parts = new String(B64D.decode(encoded), StandardCharsets.UTF_8).split("\\.");
        } catch (IllegalArgumentException e) {
            return Failure.MALFORMED;
        }
        if (parts.length != 3) {
            return Failure.MALFORMED;
        }
        Instant issued = Instant.ofEpochMilli(Long.parseLong(parts[0]));
        int bits = Integer.parseInt(parts[2]);
        Instant now = clock.instant();
        if (issued.plus(properties.challengeTtl()).isBefore(now)) {
            return Failure.EXPIRED;
        }
        if (issued.plus(properties.minFillTime()).isAfter(now)) {
            return Failure.TOO_FAST;
        }
        if (!meets(challenge + ":" + counter, bits)) {
            return Failure.WRONG;
        }
        if (spent.asMap().putIfAbsent(challenge, Boolean.TRUE) != null) {
            return Failure.SPENT;
        }
        return null;
    }

    /** Whether SHA-256 of {@code text} starts with {@code bits} zero bits. Public for the tests' solver. */
    public static boolean meets(String text, int bits) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int whole = bits / 8;
        for (int i = 0; i < whole; i++) {
            if (hash[i] != 0) {
                return false;
            }
        }
        int rest = bits % 8;
        return rest == 0 || (hash[whole] & 0xff) >>> (8 - rest) == 0;
    }

    private String sign(String encoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the browser receives: the challenge to solve and how hard it is. */
    public record Challenge(String challenge, int bits) {
    }
}
