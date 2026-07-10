// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Redirects a failed social login to {@code /?login_error=<code>}, preserving the reason so the shell can
 * show a useful message (ARCHITECTURE §8.3). The account-merging outcomes that stop a login carry a code —
 * {@code account_conflict} (identity owned by another user) and {@code link_required} (verified-email match,
 * link explicitly) — set in {@link DiscordOAuth2UserService}; anything else is {@code failed}.
 */
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = "failed";
        if (exception instanceof OAuth2AuthenticationException oauthError
                && oauthError.getError() != null
                && oauthError.getError().getErrorCode() != null
                && !oauthError.getError().getErrorCode().isBlank()) {
            code = oauthError.getError().getErrorCode();
        }
        response.sendRedirect("/?login_error=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }
}
