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

    private static final SessionProperties DEFAULTS = new SessionProperties(null, null);

    @Test
    void theSessionCookieIsHttpOnlyLaxSecureAndSiteWide() {
        String cookie = write(new SessionConfig().cookieSerializer(true, DEFAULTS));

        assertThat(cookie).startsWith(SessionConfig.SESSION_COOKIE_NAME + "=");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
        assertThat(cookie).contains("Secure");
        assertThat(cookie).contains("Path=/");
    }

    @Test
    void secureCanBeSwitchedOffForAPlainHttpLocalRunAndNothingElseChanges() {
        String cookie = write(new SessionConfig().cookieSerializer(false, DEFAULTS));

        assertThat(cookie).doesNotContain("Secure");
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
    }

    @Test
    void theCookieLastsAsLongAsTheSessionSoClosingTheBrowserDoesNotSignAnyoneOut() {
        assertThat(write(new SessionConfig().cookieSerializer(true, DEFAULTS))).contains("Max-Age=2592000");
        SessionProperties twelveHours = new SessionProperties(java.time.Duration.ofHours(12), null);
        assertThat(write(new SessionConfig().cookieSerializer(true, twelveHours))).contains("Max-Age=43200");
    }

    @Test
    void theTimeoutDefaultsTo30DaysAndTheStoreToMemoryAndATypoFailsStartup() {
        assertThat(DEFAULTS.timeout()).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(DEFAULTS.store()).isEqualTo(SessionProperties.MEMORY);
        assertThat(DEFAULTS.timeoutDays()).isEqualTo(30);
        assertThat(new SessionProperties(java.time.Duration.ofHours(1), "JDBC").store())
                .isEqualTo(SessionProperties.JDBC);
        assertThat(new SessionProperties(java.time.Duration.ofHours(1), null).timeoutDays()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SessionProperties(null, "redis"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memory");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SessionProperties(java.time.Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theInMemoryRepositoryUsesTheConfiguredTimeoutAndTheSweepDropsExpiredSessions() {
        SessionConfig config = new SessionConfig();
        SessionConfig.InMemorySessions sessions = config.inMemorySessions();
        org.springframework.session.MapSessionRepository repository = config.sessionRepository(sessions, DEFAULTS);

        org.springframework.session.MapSession live = repository.createSession();
        assertThat(live.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofDays(30));
        repository.save(live);
        org.springframework.session.MapSession stale = repository.createSession();
        stale.setMaxInactiveInterval(java.time.Duration.ofMinutes(1));
        stale.setLastAccessedTime(java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
        repository.save(stale);

        assertThat(sessions.removeExpired()).isEqualTo(1);
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(repository.findById(live.getId())).isNotNull();
    }

    @Test
    void aLiveSessionsCookieIsResentOncePerDayAndNotOnEveryRequest() throws Exception {
        SessionCookieRefreshFilter filter =
                new SessionCookieRefreshFilter(new SessionConfig().cookieSerializer(true, DEFAULTS));
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();

        MockHttpServletResponse first = run(filter, session);
        assertThat(first.getHeader(HttpHeaders.SET_COOKIE)).contains(SessionConfig.SESSION_COOKIE_NAME + "=");
        assertThat(run(filter, session).getHeader(HttpHeaders.SET_COOKIE)).isNull();

        session.setAttribute(SessionCookieRefreshFilter.REFRESHED_AT,
                System.currentTimeMillis() - java.time.Duration.ofDays(2).toMillis());
        assertThat(run(filter, session).getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=2592000");
    }

    @Test
    void aWriteNeverRefreshesSoALogoutCarriesOnlyTheExpiry() throws Exception {
        SessionCookieRefreshFilter filter =
                new SessionCookieRefreshFilter(new SessionConfig().cookieSerializer(true, DEFAULTS));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setSession(new org.springframework.mock.web.MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void aRequestWithoutASessionGetsNoCookie() throws Exception {
        SessionCookieRefreshFilter filter =
                new SessionCookieRefreshFilter(new SessionConfig().cookieSerializer(true, DEFAULTS));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, new org.springframework.mock.web.MockFilterChain());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private static MockHttpServletResponse run(SessionCookieRefreshFilter filter,
                                               org.springframework.mock.web.MockHttpSession session)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        return response;
    }

    private static String write(CookieSerializer serializer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));
        return response.getHeader(HttpHeaders.SET_COOKIE);
    }
}
