// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Redirects a failed social login to {@code /?login_error=<code>}, preserving the reason so the shell can
 * show a useful message (ARCHITECTURE §8.3). The account-merging outcomes that stop a login carry a code —
 * {@code account_conflict} (identity owned by another user) and {@code link_required} (verified-email match,
 * link explicitly) — set in {@link DiscordOAuth2UserService}; anything else is {@code failed}.
 *
 * <p>Every failure is also logged server-side: Spring only logs OAuth login failures at {@code DEBUG}, so
 * without this an operator sees the shell's generic "Login failed." with nothing in the logs to explain it.
 * A frequent cause is a lost session between the redirect and the callback ({@code authorization_request_not_found}),
 * which on a plain-http deployment usually means the {@code Secure} session cookie was dropped — serve over
 * HTTPS, or set {@code MOSAICAST_SECURITY_SECURE_COOKIE=false}.
 */
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginFailureHandler.class);

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = "failed";
        if (exception instanceof OAuth2AuthenticationException oauthError) {
            OAuth2Error error = oauthError.getError();
            if (error != null && error.getErrorCode() != null && !error.getErrorCode().isBlank()) {
                code = error.getErrorCode();
            }
        }
        // WARN (not DEBUG) so a misconfigured deployment is diagnosable from the logs; the cause carries the
        // underlying reason (bad credentials, token/userinfo error, lost session, …).
        log.warn("Social login failed (login_error={}): {}", code, exception.getMessage(), exception);
        response.sendRedirect("/?login_error=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }
}
