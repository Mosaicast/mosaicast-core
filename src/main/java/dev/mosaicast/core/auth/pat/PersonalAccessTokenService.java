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

    public PersonalAccessTokenService(PersonalAccessTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** The plaintext secret plus the stored token — the secret is available only here, once. */
    public record Issued(String secret, PersonalAccessToken token) {
    }

    /** Creates a token for a user and returns the one-time plaintext secret. */
    @Transactional
    public Issued create(UUID userId, String name) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String secret = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String prefix = secret.substring(0, Math.min(12, secret.length()));
        PersonalAccessToken token = tokens.save(
                new PersonalAccessToken(userId, name, sha256(secret), prefix));
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
        Optional<PersonalAccessToken> found = tokens.findByTokenHash(sha256(secret));
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
