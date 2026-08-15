// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.branding.AiCrawlerPolicy;
import dev.mosaicast.core.branding.SiteConfigService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code robots.txt} and the admin-configurable AI-crawler policy (ARCHITECTURE §6.6). The policy is the
 * operator's decision, so what is pinned here is that each setting produces the file it claims to — and
 * that the default produces the behaviour an existing install already had.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class RobotsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void baseUrl(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.base-url", () -> "https://podcast.test");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SiteConfigService site;

    @AfterEach
    void resetPolicy() {
        site.updateCrawlerPolicy(AiCrawlerPolicy.ALLOW, List.of());
    }

    @Test
    void theDefaultPolicySaysNothingAboutAiCrawlers() {
        // An install upgrading into this release never expressed a policy; it must not start disallowing
        // crawlers it was already serving.
        String txt = robots();

        assertThat(txt).doesNotContain("GPTBot");
        assertThat(txt).doesNotContain("ClaudeBot");
    }

    @Test
    void adminAndApiPathsAreAlwaysAskedToBeSkipped() {
        String txt = robots();

        assertThat(txt).contains("User-agent: *");
        assertThat(txt).contains("Disallow: /api/");
        assertThat(txt).contains("Disallow: /admin");
        assertThat(txt).contains("Disallow: /actuator/");
    }

    @Test
    void theSitemapIsReferencedAbsolutelyFromConfiguration() {
        assertThat(robots()).contains("Sitemap: https://podcast.test/sitemap.xml");
    }

    @Test
    void blockDisallowsTheWholeCatalog() {
        site.updateCrawlerPolicy(AiCrawlerPolicy.BLOCK, null);

        String txt = robots();

        assertThat(txt).contains("User-agent: GPTBot");
        assertThat(txt).contains("User-agent: ClaudeBot");
        assertThat(txt).contains("User-agent: CCBot");
        assertThat(txt).contains("Disallow: /");
    }

    @Test
    void customDisallowsExactlyWhatWasListed() {
        site.updateCrawlerPolicy(AiCrawlerPolicy.CUSTOM, List.of("GPTBot", "SomeBotCoreHasNeverHeardOf"));

        String txt = robots();

        assertThat(txt).contains("User-agent: GPTBot");
        // The catalog is a convenience, not the set of nameable agents.
        assertThat(txt).contains("User-agent: SomeBotCoreHasNeverHeardOf");
        assertThat(txt).doesNotContain("ClaudeBot");
    }

    @Test
    void aStoredAgentCannotInjectItsOwnDirectives() {
        // The block list is admin-supplied text written verbatim into a line-oriented format.
        site.updateCrawlerPolicy(AiCrawlerPolicy.CUSTOM, List.of("EvilBot\nDisallow: /\nUser-agent: *"));

        String txt = robots();

        // robots.txt is line-oriented, so the injection vector is a newline, not the text. The value is
        // flattened onto its own agent line: it reads as a nonsense user-agent (visible to the admin who
        // typed it) and cannot become a directive of its own.
        assertThat(txt).contains("User-agent: EvilBot Disallow: / User-agent: *");
        assertThat(countLinesStartingWith(txt, "User-agent:")).isEqualTo(2);
        // Three from the wildcard block, one for the agent — none contributed by the stored value.
        assertThat(countLinesStartingWith(txt, "Disallow:")).isEqualTo(4);
    }

    private static long countLinesStartingWith(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).count();
    }

    @Test
    void theSitemapReferenceIgnoresAForwardedHostHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-Host", "evil.example");
        String txt = rest.exchange("/robots.txt", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();

        assertThat(txt).doesNotContain("evil.example");
        assertThat(txt).contains("Sitemap: https://podcast.test/sitemap.xml");
    }

    private String robots() {
        return rest.getForObject("/robots.txt", String.class);
    }
}
