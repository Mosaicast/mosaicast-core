// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.util.Map;
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
 * The plugin-facing external-services surface end to end (ARCHITECTURE §16, SDK 0.11.0 {@code external}).
 *
 * <p>No provider is selected on this instance, which is the point rather than a gap: every gate this class
 * pins sits <em>in front of</em> the provider, and the answer a declaring plugin gets once it is past them —
 * {@code external-no-provider}, 409 — is exactly what separates "you did not ask" from "this site cannot".
 * Three fixtures carry the cases: {@code translator} declares the kind at the default {@code podcaster}
 * floor, {@code translatoropen} declares it at {@code anonymous}, and {@code good} declares no
 * {@code external} block at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginExternalIntegrationTest {

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
    void aPluginThatDeclaredNoExternalBlockHasNoSurfaceAtAll() {
        // 404, and — this is the whole ordering — a *declaring* plugin on the same instance gets 409 for the
        // same request. Identical site state, different answer, so the code cannot be read as anything but
        // the manifest gate: a plugin that never asked cannot learn from it whether this site pays for
        // translation. Deliberately not `external-no-provider`, which would send an author to an admin about
        // something no admin can grant (§16).
        assertThat(translate("good", "podcaster").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(translate("nosuch", "podcaster").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(translate("translator", "podcaster").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aVisitorBelowTheDeclaredFloorIsRefused() {
        // 403 before the provider is consulted: a below-floor caller must not be able to tell a configured
        // site from an unconfigured one either, and on a configured one this is the refusal that stops a
        // visitor spending the operator's money.
        ResponseEntity<String> refused = translate("translator", "fan");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("usedBy");
    }

    @Test
    void anAnonymousCallerIsRefusedAtTheDefaultFloor() {
        // The handle is not the permission. The shell hands `ctx.translation` to every visitor the manifest
        // declared the kind for, so this is where a signed-out one is stopped — not in the browser, where a
        // role check decides nothing.
        assertThat(translate("translator", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAnonymousFloorReallyReachesTheHandler() {
        // §16 calls `usedBy: "anonymous"` legal — a self-hosted LibreTranslate costs nothing per call — so a
        // signed-out POST has to get past the security filter, which otherwise requires a session for every
        // non-GET under /api/plugins/**. 409 is the proof: it is the *provider* refusing, which means both
        // gates let it through. A 401 here would mean the declaration was a lie.
        assertThat(translate("translatoropen", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** POSTs a translation as the given dev role, or signed out when {@code role} is null. */
    private ResponseEntity<String> translate(String pluginId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (role != null) {
            DevLogin.Cookies cookies = DevLogin.login(rest, role);
            headers.add(HttpHeaders.COOKIE, cookies.session() + "; " + cookies.xsrf());
            headers.add("X-XSRF-TOKEN", cookies.token());
        } else {
            // An anonymous write still needs the CSRF pair: the token cookie is issued to everyone, and this
            // endpoint is not exempt just because the caller has no session.
            String token = DevLogin.token(rest);
            headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
            headers.add("X-XSRF-TOKEN", token);
        }
        HttpEntity<Map<String, String>> body =
                new HttpEntity<>(Map.of("text", "The lighthouse", "to", "de"), headers);
        return rest.exchange("/api/plugins/" + pluginId + "/external/translation", HttpMethod.POST, body,
                String.class);
    }
}
