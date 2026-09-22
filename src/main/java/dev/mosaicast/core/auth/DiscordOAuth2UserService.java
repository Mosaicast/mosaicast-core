// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.ExplicitLinkRequiredException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Maps a Discord {@code oauth2Login} into a Mosaicast {@link User} via the account-merging rules
 * (ARCHITECTURE §8.1/§8.3). Discord is plain OAuth2 (not OIDC), so this extends the standard user
 * service, reads {@code /users/@me}, and applies {@link AccountService}.
 *
 * <p>The returned principal's name is the Mosaicast user id and it carries a single {@code ROLE_*}
 * authority — the same shape the dev-login bypass produces, so {@link CurrentUser} reads both uniformly.
 */
@Service
public class DiscordOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(DiscordOAuth2UserService.class);

    /** The synthetic attribute holding the Mosaicast user id; also the principal name key. */
    public static final String UID_ATTRIBUTE = "uid";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final AccountService accounts;

    public DiscordOAuth2UserService(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User discordUser = delegate.loadUser(request);
        Map<String, Object> attrs = discordUser.getAttributes();

        // Rejected rather than stringified. `String.valueOf(null)` is the string "null", and because the
        // identity table's unique key is (provider, external_id), the first login missing an id would have
        // created an account keyed on that literal — and every subsequent one would have logged into it
        // (core#194). Discord always sends an id; a response without one is a protocol failure, not a user.
        Object rawId = attrs.get("id");
        if (rawId == null || String.valueOf(rawId).isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("missing_identity"),
                    "The provider did not return an account id.", null);
        }
        String externalId = String.valueOf(rawId);
        String email = attrs.get("email") == null ? null : String.valueOf(attrs.get("email"));
        boolean verified = Boolean.TRUE.equals(attrs.get("verified"));
        String displayName = displayName(attrs);
        String avatarRef = avatarRef(attrs.get("avatar"));

        IdentityClaim claim =
                new IdentityClaim("discord", externalId, email, verified, displayName, avatarRef);
        // A logged-in user linking a new provider from settings (§8.3 case 2): the previous session's
        // authentication is still in the context during the callback.
        UUID currentUserId = CurrentUser.id(SecurityContextHolder.getContext().getAuthentication()).orElse(null);

        User user = resolve(claim, currentUserId);
        return toPrincipal(user, attrs);
    }

    /**
     * Applies the merging rules, translating the domain outcomes that should stop the login into
     * {@link OAuth2AuthenticationException}s so {@code oauth2Login().failureUrl(...)} redirects cleanly
     * (§8.3) instead of surfacing a 500.
     */
    private User resolve(IdentityClaim claim, UUID currentUserId) {
        try {
            return attemptResolve(claim, currentUserId);
        } catch (DataIntegrityViolationException e) {
            // Two callbacks for the same unknown identity: both find nothing, both create a user, and one
            // loses the race on uq_identity_provider_external. The loser used to reach the caller as a raw
            // 500 rather than the designed failure page, because only the two domain exceptions below were
            // translated (core#194). By the time we are here the identity exists, so resolving again is the
            // ordinary "known identity" path — and if it somehow still fails, the failure is real.
            log.debug("Concurrent first login for {}; resolving against the row that won", claim.provider());
            return attemptResolve(claim, currentUserId);
        }
    }

    private User attemptResolve(IdentityClaim claim, UUID currentUserId) {
        try {
            return accounts.resolveLogin(claim, currentUserId);
        } catch (ConflictException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_conflict"), e.getMessage(), e);
        } catch (ExplicitLinkRequiredException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("link_required"), e.getMessage(), e);
        }
    }

    private static OAuth2User toPrincipal(User user, Map<String, Object> discordAttrs) {
        Map<String, Object> attrs = new HashMap<>(discordAttrs);
        attrs.put(UID_ATTRIBUTE, user.getId().toString());
        return new DefaultOAuth2User(CurrentUser.authoritiesFor(user.getRole()), attrs, UID_ATTRIBUTE);
    }

    private static String displayName(Map<String, Object> attrs) {
        Object global = attrs.get("global_name");
        if (global != null && !String.valueOf(global).isBlank()) {
            return String.valueOf(global);
        }
        Object username = attrs.get("username");
        return username != null ? String.valueOf(username) : "Discord user";
    }

    /**
     * Discord's avatar hash, or null when the account has no custom picture.
     *
     * <p>The hash, deliberately not a URL (§8.7). A stored URL contains the Discord snowflake, and every
     * place that handled it was one `<img src>` away from publishing an identifier social login is meant to
     * keep server-side. The host composes the URL when it fetches, from a constant host — which is why the
     * user id this took as its first parameter, and never read, is gone.
     */
    private static String avatarRef(Object avatarHash) {
        if (avatarHash == null || String.valueOf(avatarHash).isBlank()) {
            return null;
        }
        return String.valueOf(avatarHash);
    }
}
