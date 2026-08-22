// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deleting an account, including the half core does not own (ARCHITECTURE §12, SDK {@code UserDataHandler}).
 *
 * <p>The interesting cases are the ones that used to be silent. A plugin whose handler throws, and a plugin
 * an operator switched off before the deletion ran, both leave data behind — and both would otherwise end
 * with the person told their data was gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class AccountErasureIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void deletingAnAccountAsksEveryPluginAndSaysWhatIsLeft() {
        Session fan = devLogin("fan");
        String userId = userIdOf(fan);

        // Something of theirs in the USER scope, which is host-owned and so core's to drop.
        rest.exchange("/api/plugins/good/data/user/me/marks", HttpMethod.PUT,
                fan.write("{\"a\":1}"), Void.class);

        ResponseEntity<String> receipt = rest.exchange(
                "/api/me", HttpMethod.DELETE, fan.plain(), String.class);

        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receipt.getBody()).contains("\"complete\":true").contains("\"outstanding\":[]");

        // The plugin's own erasure is invisible to core by construction, so the fixture leaves a mark.
        assertThat(rest.getForEntity("/api/plugins/good/data/site/main/erased:" + userId, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // And the host's half is gone: the session no longer authenticates anybody.
        assertThat(rest.exchange("/api/me", HttpMethod.GET, fan.plain(), String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void aHandlerThatThrowsLeavesARecordedDebtRatherThanASilence() {
        Session admin = devLogin("admin");
        setFailErasure(admin, true);
        try {
            Session doomed = devLogin("fan");
            ResponseEntity<String> receipt = rest.exchange(
                    "/api/me", HttpMethod.DELETE, doomed.plain(), String.class);

            // The account still goes — what is owed is owed whether or not a plugin cooperated — but the
            // answer says so rather than claiming completion.
            assertThat(receipt.getBody()).contains("\"complete\":false").contains("good");

            String outstanding = rest.exchange("/api/admin/erasures", HttpMethod.GET, admin.plain(),
                    String.class).getBody();
            assertThat(outstanding).contains("\"pluginId\":\"good\"").contains("FAILED")
                    .contains("told to fail");

            // Fix the plugin, retry, and the debt settles — the same handler, run again, which is why the
            // contract requires idempotence.
            setFailErasure(admin, false);
            String afterRetry = rest.exchange("/api/admin/erasures/retry", HttpMethod.POST,
                    admin.write("", true), String.class).getBody();
            assertThat(afterRetry).doesNotContain("\"pluginId\":\"good\"");
        } finally {
            setFailErasure(admin, false);
        }
    }

    @Test
    void aPluginSwitchedOffWhenTheAccountWentIsAskedWhenItComesBack() {
        Session admin = devLogin("admin");
        rest.exchange("/api/admin/plugins/blobs/enabled?value=false", HttpMethod.PUT,
                admin.write("", true), String.class);
        try {
            Session leaving = devLogin("fan");
            String userId = userIdOf(leaving);

            ResponseEntity<String> receipt = rest.exchange(
                    "/api/me", HttpMethod.DELETE, leaving.plain(), String.class);

            // The silent skip this whole record exists to prevent: a disabled plugin's tables are still
            // there, its extensions are not loaded, and nothing would otherwise have noticed.
            assertThat(receipt.getBody()).contains("\"complete\":false").contains("blobs");
            assertThat(rest.exchange("/api/admin/erasures", HttpMethod.GET, admin.plain(), String.class)
                    .getBody()).contains("\"pluginId\":\"blobs\"").contains("not active");

            rest.exchange("/api/admin/plugins/blobs/enabled?value=true", HttpMethod.PUT,
                    admin.write("", true), String.class);

            // Switching it back on is the moment it can answer, so that is when the debt clears.
            String afterEnable = rest.exchange("/api/admin/erasures", HttpMethod.GET, admin.plain(),
                    String.class).getBody();
            assertThat(afterEnable).doesNotContain("\"pluginId\":\"blobs\"");
            assertThat(rest.getForEntity("/api/plugins/blobs/data/site/main/erased:" + userId, String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            rest.exchange("/api/admin/plugins/blobs/enabled?value=true", HttpMethod.PUT,
                    admin.write("", true), String.class);
        }
    }

    private void setFailErasure(Session admin, boolean fail) {
        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"failErasure\":" + fail + "}", true), String.class);
    }

    private String userIdOf(Session session) {
        String me = rest.exchange("/api/me", HttpMethod.GET, session.plain(), String.class).getBody();
        int start = me.indexOf("\"id\":\"") + 6;
        return me.substring(start, me.indexOf('"', start));
    }

    private Session devLogin(String role) {
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
    }

    /** A logged-in session's cookies, with helpers for reads and CSRF-carrying writes. */
    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<Void> plain() {
            return new HttpEntity<>(headers(true));
        }

        HttpEntity<String> write(String body) {
            return write(body, true);
        }

        HttpEntity<String> write(String body, boolean withCsrf) {
            HttpHeaders headers = headers(withCsrf);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
        }

        private HttpHeaders headers(boolean withCsrf) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            if (withCsrf) {
                headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            }
            return headers;
        }
    }
}
