// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Server-side sessions (ARCHITECTURE §8.5): an httpOnly cookie backed by a server-side store, with
 * <strong>no JWT</strong>, so a ban, a role change or a logout takes effect immediately.
 *
 * <p>Two stores, picked by {@code MOSAICAST_SESSION_STORE} ({@link SessionProperties}):
 * <ul>
 *   <li>{@code memory}, the default: sessions live in this process and a restart signs everyone out.</li>
 *   <li>{@code jdbc}: sessions live in Postgres (V43), so they survive a restart and could be shared by
 *       several instances.</li>
 * </ul>
 * Both expire a session after {@code MOSAICAST_SESSION_TIMEOUT} without a visit (default 30 days). Before
 * that setting existed the in-memory store used Spring Session's 30-minute default, so a listener who came
 * back the next day had to sign in with Discord again.
 */
@Configuration
@EnableSpringHttpSession
public class SessionConfig {

    /** The session cookie name — referenced here and by the logout handler in SecurityConfig. */
    public static final String SESSION_COOKIE_NAME = "MOSAICAST_SESSION";

    /** The in-memory sessions, kept so {@link SessionSweep} can drop the expired ones. */
    public static final class InMemorySessions {

        private final Map<String, Session> sessions = new ConcurrentHashMap<>();

        Map<String, Session> map() {
            return sessions;
        }

        /**
         * Removes every expired session; returns how many went. {@link MapSessionRepository} only notices an
         * expired session when somebody asks for it again, so one nobody returns to (an abandoned Discord
         * login, say) would otherwise stay in memory for good.
         */
        public int removeExpired() {
            int before = sessions.size();
            sessions.values().removeIf(Session::isExpired);
            return before - sessions.size();
        }

        /** How many sessions are held right now. */
        public int size() {
            return sessions.size();
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "mosaicast.session", name = "store", havingValue = SessionProperties.MEMORY,
            matchIfMissing = true)
    InMemorySessions inMemorySessions() {
        return new InMemorySessions();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mosaicast.session", name = "store", havingValue = SessionProperties.MEMORY,
            matchIfMissing = true)
    MapSessionRepository sessionRepository(InMemorySessions sessions, SessionProperties properties) {
        MapSessionRepository repository = new MapSessionRepository(sessions.map());
        repository.setDefaultMaxInactiveInterval(properties.timeout());
        return repository;
    }

    @Bean
    @ConditionalOnProperty(prefix = "mosaicast.session", name = "store", havingValue = SessionProperties.JDBC)
    JdbcIndexedSessionRepository jdbcSessionRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                                       SessionProperties properties) {
        JdbcIndexedSessionRepository repository = new JdbcIndexedSessionRepository(jdbc,
                new TransactionTemplate(transactions));
        repository.setDefaultMaxInactiveInterval(properties.timeout());
        // An upsert for attributes instead of insert-then-update; the Postgres flavour Spring Session ships.
        new PostgreSqlJdbcIndexedSessionRepositoryCustomizer().customize(repository);
        return repository;
    }

    /**
     * The session cookie. {@code Secure} defaults to {@code true} (safe for a TLS deployment and for
     * {@code http://localhost}, which browsers treat as a secure context); the {@code dev} profile sets
     * {@code mosaicast.security.secure-cookie=false} for plain-http local runs. It lives as long as the session
     * does, so closing the browser does not sign anyone out; {@link SessionCookieRefreshFilter} keeps its
     * expiry moving with the session's.
     */
    @Bean
    CookieSerializer cookieSerializer(
            @Value("${mosaicast.security.secure-cookie:true}") boolean secureCookie, SessionProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE_NAME);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secureCookie);
        serializer.setCookiePath("/");
        serializer.setCookieMaxAge((int) Math.min(Integer.MAX_VALUE, properties.timeout().toSeconds()));
        return serializer;
    }

    /** Runs right after Spring Session's own filter, so the session it resolved is the one refreshed. */
    @Bean
    FilterRegistrationBean<SessionCookieRefreshFilter> sessionCookieRefreshFilter(CookieSerializer cookies) {
        FilterRegistrationBean<SessionCookieRefreshFilter> registration =
                new FilterRegistrationBean<>(new SessionCookieRefreshFilter(cookies));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
        return registration;
    }
}
