// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

/**
 * The session cookie's attributes (core#191): every one of them is a security property, and none was
 * asserted anywhere — a refactor dropping {@code HttpOnly} or {@code SameSite} would have passed the suite.
 */
class SessionConfigTest {

    @Test
    void theSessionCookieIsHttpOnlyLaxSecureAndSiteWide() {
        String cookie = write(new SessionConfig().cookieSerializer(true));

        assertThat(cookie).startsWith(SessionConfig.SESSION_COOKIE_NAME + "=");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
        assertThat(cookie).contains("Secure");
        assertThat(cookie).contains("Path=/");
    }

    @Test
    void secureCanBeSwitchedOffForAPlainHttpLocalRunAndNothingElseChanges() {
        String cookie = write(new SessionConfig().cookieSerializer(false));

        assertThat(cookie).doesNotContain("Secure");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
    }

    private static String write(CookieSerializer serializer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));
        return response.getHeader(HttpHeaders.SET_COOKIE);
    }
}
