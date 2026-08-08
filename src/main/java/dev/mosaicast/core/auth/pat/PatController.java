// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manage the current user's personal access tokens (ARCHITECTURE §8.5). Creating tokens is limited to
 * podcasters and admins — automation (e.g. stats/MAT upload) is a podcaster capability. The secret is
 * returned only once, on creation.
 */
@RestController
@RequestMapping("/api/me/tokens")
public class PatController {

    private final PersonalAccessTokenService tokens;

    public PatController(PersonalAccessTokenService tokens) {
        this.tokens = tokens;
    }

    /** Body for creating a token. */
    public record CreateToken(@NotBlank String name) {
    }

    /**
     * One token as its owner sees it. Never the secret — that exists in plaintext once, in
     * {@link CreatedToken}.
     *
     * @param expiresAt when it stops working, or {@code null} for a token issued before expiry existed. Shown
     *                  so someone whose automation stopped can see why, rather than finding the row gone
     */
    public record TokenView(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt,
                            Instant expiresAt) {
        static TokenView of(PersonalAccessToken token) {
            return new TokenView(token.getId(), token.getName(), token.getPrefix(),
                    token.getCreatedAt(), token.getLastUsedAt(), token.getExpiresAt());
        }
    }

    /** Creation response — includes the plaintext secret, shown this one time only. */
    public record CreatedToken(UUID id, String name, String prefix, String secret, Instant createdAt,
                               Instant expiresAt) {
    }

    @GetMapping
    public List<TokenView> list(Authentication authentication) {
        return tokens.list(currentUserId(authentication)).stream().map(TokenView::of).toList();
    }

    /** Podcaster capability (§8.5) — enforced declaratively; the denial renders as problem+json 403. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PODCASTER','ADMIN')")
    public CreatedToken create(@RequestBody @jakarta.validation.Valid CreateToken request,
                               Authentication authentication) {
        var issued = tokens.create(currentUserId(authentication), request.name());
        return new CreatedToken(
                issued.token().getId(), issued.token().getName(), issued.token().getPrefix(),
                issued.secret(), issued.token().getCreatedAt(), issued.token().getExpiresAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, Authentication authentication) {
        tokens.revoke(currentUserId(authentication), id);
    }

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
