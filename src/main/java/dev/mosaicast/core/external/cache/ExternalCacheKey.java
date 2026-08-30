// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.cache;

import dev.mosaicast.core.external.ExternalServiceKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Derives the identity of one cached external-service result (ARCHITECTURE §12.7). */
public final class ExternalCacheKey {

    /**
     * Separator between components.
     *
     * <p>A NUL rather than a dash or a slash: without it, {@code ("ab","c")} and {@code ("a","bc")} hash the
     * same, and a provider id containing the separator could impersonate the next component. No component
     * here can contain a NUL, so the boundary is unforgeable.
     */
    private static final char SEP = '\0';

    private ExternalCacheKey() {
    }

    /**
     * @param configFingerprint hash of the non-secret settings — see {@code ProviderConfig.fingerprint()}
     * @param cacheIdentity     what the kind says makes two requests the same request
     */
    public static String of(ExternalServiceKind kind, String providerId, String configFingerprint,
                            String cacheIdentity) {
        return sha256(kind.id() + SEP + providerId + SEP + configFingerprint + SEP + cacheIdentity);
    }

    /** Hex sha-256, also used by kinds to hash a long input into their identity string. */
    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
