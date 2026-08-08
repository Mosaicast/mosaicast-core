// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import dev.mosaicast.core.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies personal access tokens (ARCHITECTURE §8.5). The plaintext token is returned once at
 * creation; only its SHA-256 hash is persisted, so a database leak never yields usable tokens.
 */
@Service
public class PersonalAccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenService.class);

    /** Token prefix so a leaked/committed token is greppable and identifiable as a Mosaicast token. */
    private static final String TOKEN_PREFIX = "mcp_";
    private static final int TOKEN_BYTES = 32;

    /** Coarsen the "last used" write: skip it if it was updated within this window (avoids per-request writes). */
    private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

    private final PersonalAccessTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    /**
     * How many live tokens one user may hold, and how long a new one lasts.
     *
     * <p>Both were unbounded. A cap is not much of a security control on its own — a user who can mint one
     * token can mint one more — but an unbounded list is how a credential store becomes something nobody
     * reviews, and a hard stop makes "revoke the old one" the obvious move instead of "add another".
     */
    private final int maxPerUser;
    private final int lifetimeDays;

    public PersonalAccessTokenService(
            PersonalAccessTokenRepository tokens,
            @Value("${mosaicast.security.pat-max-per-user:20}") int maxPerUser,
            @Value("${mosaicast.security.pat-lifetime-days:365}") int lifetimeDays) {
        this.tokens = tokens;
        this.maxPerUser = maxPerUser;
        this.lifetimeDays = lifetimeDays;
    }

    /** The plaintext secret plus the stored token — the secret is available only here, once. */
    public record Issued(String secret, PersonalAccessToken token) {
    }

    /** Creates a token for a user and returns the one-time plaintext secret. */
    @Transactional
    public Issued create(UUID userId, String name) {
        long live = tokens.countByUserId(userId);
        if (live >= maxPerUser) {
            throw new IllegalArgumentException(
                    ("You already have %d access tokens, which is the limit. Revoke one you no longer use "
                            + "before creating another.").formatted(maxPerUser));
        }
        // 0 or less means "no expiry", for an operator who genuinely wants long-lived automation credentials
        // and has decided that knowingly.
        Instant expiresAt = lifetimeDays > 0 ? Instant.now().plus(Duration.ofDays(lifetimeDays)) : null;
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String secret = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String prefix = secret.substring(0, Math.min(12, secret.length()));
        PersonalAccessToken token = tokens.save(
                new PersonalAccessToken(userId, name, sha256(secret), prefix, expiresAt));
        // The prefix only — never the secret, which exists in plaintext exactly once, in the response.
        log.info("Access token '{}' ({}…) created for user {}", name, prefix, userId);
        return new Issued(secret, token);
    }

    @Transactional(readOnly = true)
    public List<PersonalAccessToken> list(UUID userId) {
        return tokens.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        PersonalAccessToken token = tokens.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> new NotFoundException("Token not found: " + tokenId));
        tokens.delete(token);
        log.info("Access token '{}' revoked for user {}", token.getName(), userId);
    }

    /**
     * Resolves a presented secret to its token, marking it used. Returns empty for an unknown token, so a
     * caller never distinguishes "bad token" from "no token".
     */
    @Transactional
    public Optional<PersonalAccessToken> authenticate(String secret) {
        if (secret == null || !secret.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<PersonalAccessToken> found = tokens.findByTokenHash(sha256(secret))
                // An expired token authenticates nobody. Filtered rather than deleted: the row is the record
                // that the credential existed, and the owner should see it in their list to understand why
                // their automation stopped rather than finding it silently gone.
                .filter(token -> !token.isExpired(Instant.now()));
        found.ifPresent(token -> {
            // Only persist "last used" when it is stale, so hot automation paths don't write every request.
            Instant last = token.getLastUsedAt();
            if (last == null || last.isBefore(Instant.now().minus(LAST_USED_THROTTLE))) {
                token.markUsed();
                tokens.save(token);
            }
        });
        return found;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
