// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A sign-in started through the edge behind a TLS-terminating proxy (ARCHITECTURE §18.4) sends Discord
 * back to the public origin, not to the address core listens on. Core builds the callback from the
 * forwarded headers the edge passes along; a wrong one is a launch where nobody on the team can sign in.
 *
 * <p>Not the dev profile: dev-login and a configured Discord are refused together on purpose, so this is
 * the one place the combination "Discord configured, behind a proxy" is exercised before production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {"DISCORD_CLIENT_ID=test-client", "DISCORD_CLIENT_SECRET=test-secret"})
class OAuthBehindProxyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Test
    void theDiscordCallbackPointsAtThePublicOriginTheProxyNames() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/oauth2/authorization/discord"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "launch.example")
                .header("X-Forwarded-For", "203.0.113.7")
                .GET()
                .build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith("https://discord.com/oauth2/authorize");
        assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
                .contains("redirect_uri=https://launch.example/login/oauth2/code/discord");
    }

    @Test
    void theSitePolicyListsDiscordOnceItIsConfigured() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/site/access")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).contains("\"id\":\"discord\"", "\"path\":\"/oauth2/authorization/discord\"");
    }
}
