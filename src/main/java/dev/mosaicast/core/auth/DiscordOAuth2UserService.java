// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.ExplicitLinkRequiredException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

        String externalId = String.valueOf(attrs.get("id"));
        String email = attrs.get("email") == null ? null : String.valueOf(attrs.get("email"));
        boolean verified = Boolean.TRUE.equals(attrs.get("verified"));
        String displayName = displayName(attrs);
        String avatarUrl = avatarUrl(externalId, attrs.get("avatar"));

        IdentityClaim claim = new IdentityClaim("discord", externalId, email, verified, displayName, avatarUrl);
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

    private static String avatarUrl(String userId, Object avatarHash) {
        if (avatarHash == null || String.valueOf(avatarHash).isBlank()) {
            return null;
        }
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png";
    }
}
