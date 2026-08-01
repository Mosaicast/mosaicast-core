// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
 * End-to-end plugin loading (ARCHITECTURE §7). Boots the host against a real staged plugins dir holding a
 * good fixture plugin plus two deliberately-broken ones (incompatible {@code platformApi}; declared schema).
 * Asserts the good plugin loads and its {@code register(ctx)} ran (its seeded doc round-trips through the
 * HTTP surface, an asset serves), the broken ones are rejected-with-reason while the host still booted
 * (failure isolation, §7.8), and the doc surface enforces read/write access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginLoadingIntegrationTest {

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
    void goodPluginIsInPublicManifestAndBrokenOnesAreNot() {
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manifest.getBody()).contains("\"id\":\"good\"");
        assertThat(manifest.getBody()).doesNotContain("\"broken\"").doesNotContain("\"schema\"");
    }

    @Test
    void adminSeesLoadStateWithReasons() {
        Session admin = devLogin("admin");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/plugins", HttpMethod.GET, admin.get(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"id\":\"good\"").contains("LOADED");
        assertThat(body).contains("REJECTED");
        // The two rejections carry a reason each (platformApi mismatch / schema storage).
        assertThat(body).containsIgnoringCase("platformApi");
        assertThat(body).containsIgnoringCase("schema");
    }

    @Test
    void registerRanAndSeededDocStore() {
        // FixturePlugin.register put a site-scoped greeting + episode-count; both read back publicly.
        ResponseEntity<String> greeting =
                rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class);
        assertThat(greeting.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(greeting.getBody()).contains("hello from fixture");

        ResponseEntity<String> count =
                rest.getForEntity("/api/plugins/good/data/site/main/episode-count", String.class);
        assertThat(count.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count.getBody().trim()).isEqualTo("0");
    }

    @Test
    void missingDocIs404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/good/data/site/main/absent", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assetIsServed() {
        ResponseEntity<String> response =
                rest.getForEntity("/plugins/good/assets/fixture.js", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("export const fixture");
    }

    @Test
    void scopeEpisodesResolvePublicly() {
        // The shell reads this to fill ctx.episodes; site scope resolves to a (possibly empty) JSON array.
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/scope-episodes?type=site&id=main", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[");
    }

    @Test
    void unknownPluginIs404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/nope/data/site/main/x", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void writeRequiresAuthAndRole() {
        String path = "/api/plugins/good/data/episode/ep-1/note";
        String body = "{\"text\":\"a highlight\"}";

        // Anonymous write is refused: a cookie-less, non-bearer PUT fails CSRF (403) before it could reach
        // a handler — either way, no anonymous writes.
        ResponseEntity<String> anon = rest.exchange(
                path, HttpMethod.PUT, json(body), String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A podcaster (the write floor) succeeds, and the note reads back publicly.
        Session podcaster = devLogin("podcaster");
        ResponseEntity<String> write = rest.exchange(
                path, HttpMethod.PUT, podcaster.write(body, true), String.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> read = rest.getForEntity(path, String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).contains("a highlight");

        // Delete is idempotent.
        assertThat(rest.exchange(path, HttpMethod.DELETE, podcaster.delete(true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(path, HttpMethod.DELETE, podcaster.delete(true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void disablingAPluginClosesEverySurfaceItServes() {
        Session admin = devLogin("admin");
        try {
            setEnabled(admin, "good", false);

            // Dropped from the manifest, so the shell unmounts it on its next fetch (§7.8).
            assertThat(rest.getForEntity("/api/plugins/manifest", String.class).getBody())
                    .doesNotContain("\"id\":\"good\"");
            // Data surface and bundle behave exactly like an unknown plugin.
            assertThat(rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.getForEntity("/plugins/good/assets/fixture.js", String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            // The admin list still shows it — you must be able to find and re-enable it.
            assertThat(adminPlugins(admin)).contains("\"id\":\"good\"").contains("\"enabled\":false");
        } finally {
            setEnabled(admin, "good", true);
        }

        // Re-enabling restores the surfaces; nothing was deleted.
        assertThat(rest.getForEntity("/api/plugins/manifest", String.class).getBody())
                .contains("\"id\":\"good\"");
        assertThat(rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void disabledPluginCannotWriteToItsDocStore() {
        Session admin = devLogin("admin");
        Session podcaster = devLogin("podcaster");
        String path = "/api/plugins/good/data/site/main/blocked";
        try {
            setEnabled(admin, "good", false);
            // 404 at the HTTP surface; the in-process guard in PluginDataService covers the plugin's own
            // threads, which no HTTP test can reach.
            assertThat(rest.exchange(path, HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            setEnabled(admin, "good", true);
        }
    }

    @Test
    void configOverrideBeatsTheManifestDefaultAndClearingRestoresIt() {
        Session admin = devLogin("admin");
        assertThat(configValueOf(admin, "refreshIntervalMinutes")).isEqualTo("30");

        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":5}", true), String.class);
        assertThat(configValueOf(admin, "refreshIntervalMinutes")).isEqualTo("5");
        assertThat(adminPlugins(admin)).contains("\"overridden\":true");

        // A JSON null clears the override rather than pinning an empty value.
        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":null}", true), String.class);
        assertThat(configValueOf(admin, "refreshIntervalMinutes")).isEqualTo("30");
    }

    @Test
    void configRejectsUndeclaredFieldsAndWrongTypes() {
        Session admin = devLogin("admin");
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"nosuchfield\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":\"soon\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void podcasterMayEditOnlyTheFieldsDelegatedToThem() {
        Session podcaster = devLogin("podcaster");
        // refreshIntervalMinutes is editableBy podcaster …
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                podcaster.write("{\"refreshIntervalMinutes\":15}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // … apiToken is admin-only.
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                podcaster.write("{\"apiToken\":\"secret\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A fan has no business here at all — the filter rule stops them before the controller.
        Session fan = devLogin("fan");
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                fan.write("{\"refreshIntervalMinutes\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Session admin = devLogin("admin");
        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":null}", true), String.class);
    }

    @Test
    void purgeRemovesDocsButKeepsHostSettings() {
        Session admin = devLogin("admin");
        Session podcaster = devLogin("podcaster");
        String path = "/api/plugins/good/data/episode/ep-purge/note";
        rest.exchange(path, HttpMethod.PUT, podcaster.write("{\"text\":\"bye\"}", true), String.class);
        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":7}", true), String.class);

        ResponseEntity<String> purge = rest.exchange(
                "/api/admin/plugins/good/purge", HttpMethod.POST, admin.write("", true), String.class);
        assertThat(purge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(purge.getBody()).contains("\"purged\":");

        // Documents are gone — including the ones the plugin's register(ctx) seeded at boot …
        assertThat(rest.getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // … while the plugin keeps serving and its config survives: purging data is not a reset.
        assertThat(configValueOf(admin, "refreshIntervalMinutes")).isEqualTo("7");
        assertThat(rest.getForEntity("/api/plugins/manifest", String.class).getBody())
                .contains("\"id\":\"good\"");

        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"refreshIntervalMinutes\":null}", true), String.class);
        // Restore what register(ctx) seeded at boot: this context is shared with the other tests, and only a
        // restart would re-run register().
        rest.exchange("/api/plugins/good/data/site/main/greeting", HttpMethod.PUT,
                podcaster.write("\"hello from fixture\"", true), String.class);
        rest.exchange("/api/plugins/good/data/site/main/episode-count", HttpMethod.PUT,
                podcaster.write("0", true), String.class);
    }

    @Test
    void togglingAnUnknownPluginIs404() {
        Session admin = devLogin("admin");
        assertThat(rest.exchange("/api/admin/plugins/nope/enabled?value=false", HttpMethod.PUT,
                admin.write("", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deepLinkCarriesThePluginsShareMetadata() {
        // A link scraper runs no JS, so the preview has to be in the HTML the server returns (§6.4). The
        // fixture's ShareMetadataProvider answers for the "shared" subpath.
        ResponseEntity<String> shared = rest.getForEntity("/p/good/shared", String.class);
        assertThat(shared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shared.getBody())
                .contains("og:title")
                .contains("Fixture shared page")
                .contains("A page shared from the fixture");
    }

    @Test
    void deepLinkWithoutAMatchFallsBackToSiteMetadata() {
        ResponseEntity<String> other = rest.getForEntity("/p/good/somewhere-else", String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getBody()).contains("og:title").doesNotContain("Fixture shared page");
    }

    @Test
    void deepLinkOfAPluginWithoutAPageSlotIsARealNotFound() {
        // The `nopage` fixture loads fine but declares no `page` slot, so the shell renders its not-found
        // view — the status must say the same thing. Regression: this answered 200 (a soft-404, §6.6).
        ResponseEntity<String> response = rest.getForEntity("/p/nopage/anything", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // It is still a loaded plugin in every other respect.
        assertThat(rest.getForEntity("/api/plugins/manifest", String.class).getBody())
                .contains("\"id\":\"nopage\"");
    }

    @Test
    void deepLinkOfAnUnknownPluginIsARealNotFound() {
        // §6.6: no soft-404 — the status is a real 404 even though the shell is still returned so the client
        // route can render its own not-found page.
        ResponseEntity<String> response = rest.getForEntity("/p/nope/whatever", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sitemapCarriesPluginUrlsButOnlyWithinTheirOwnNamespace() {
        ResponseEntity<String> sitemap = rest.getForEntity("/sitemap.xml", String.class);
        assertThat(sitemap.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sitemap.getBody()).contains("<urlset").contains("/p/good/shared");
        // The fixture also offers a URL outside its namespace; a plugin cannot inject site URLs.
        assertThat(sitemap.getBody()).doesNotContain("/episodes/not-mine");
    }

    @Test
    void disablingAPluginRemovesItsDeepLinksAndSitemapEntries() {
        Session admin = devLogin("admin");
        try {
            setEnabled(admin, "good", false);
            assertThat(rest.getForEntity("/p/good/shared", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.getForEntity("/sitemap.xml", String.class).getBody())
                    .doesNotContain("/p/good/shared");
        } finally {
            setEnabled(admin, "good", true);
        }
    }

    @Test
    void consentAggregatesWhatActivePluginsDeclared() {
        // The core asks nothing of its own, so the payload is empty until a plugin declares a service; the
        // fixture declares an `analytics` one and a `necessary` one, and `necessary` is never asked about —
        // it is not optional (§12.5).
        ResponseEntity<String> consent = rest.getForEntity("/api/consent", String.class);
        assertThat(consent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consent.getBody())
                .contains("\"id\":\"analytics\"")
                .contains("\"provider\"")
                .contains("\"fingerprint\"");
        assertThat(consent.getBody()).doesNotContain("\"id\":\"necessary\"");
        // What core itself stores is disclosed rather than asked about, as i18n keys the shell resolves.
        assertThat(consent.getBody())
                .contains("\"essential\"")
                .contains("mc.consent")
                .contains("consent.purpose.progress");
        // The visitor-facing payload names services and providers, never plugins: the wording rules for
        // §12.5 are enforced by what the endpoint is able to say, not by review. It also carries no `hosts`
        // — an origin is an operator's unit, not a visitor's, and it belongs to the audit and the CSP.
        // (The provider's own `privacyUrl` is a different thing and is meant to be there.)
        assertThat(consent.getBody())
                .doesNotContain("\"pluginId\"")
                .doesNotContain("\"good\"")
                .doesNotContain("\"hosts\"");
    }

    @Test
    void aVisitorWhoRefusedGetsAPolicyWithoutThatOrigin() {
        // Without the cookie — a first visit, or a refusal — nothing optional is allowed. This is the half of
        // consent a plugin cannot ignore: `ctx.consent.has()` is advisory, a blocked connection is not.
        HttpHeaders refused = rest.getForEntity("/api/meta", String.class).getHeaders();
        String refusedCsp = refused.getFirst("Content-Security-Policy");
        assertThat(refusedCsp).doesNotContain("https://plausible.example");
        // A `necessary` service is never asked about, so no decision can withdraw it.
        assertThat(refusedCsp).contains("https://necessary.example");
        // The policy now differs between visitors, so a shared cache must key on the cookie.
        assertThat(refused.get(HttpHeaders.VARY)).anySatisfy(v -> assertThat(v).containsIgnoringCase("cookie"));

        HttpHeaders granted = rest.exchange("/api/meta", HttpMethod.GET,
                new HttpEntity<>(cookieHeader("mc_consent=analytics")), String.class).getHeaders();
        assertThat(granted.getFirst("Content-Security-Policy")).contains("https://plausible.example");

        // A forged category widens nothing: the manifest, not the cookie, decides which origins exist.
        HttpHeaders forged = rest.exchange("/api/meta", HttpMethod.GET,
                new HttpEntity<>(cookieHeader("mc_consent=analytics.evil")), String.class).getHeaders();
        assertThat(forged.getFirst("Content-Security-Policy")).doesNotContain("evil");
    }

    private static HttpHeaders cookieHeader(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    @Test
    void theConsentAuditAttributesEveryDeclarationToItsPlugin() {
        // The operator's view is the mirror image: it has to name the plugin, the origins and the services
        // nobody is asked about, because "why is this host in the CSP?" is an operator question.
        assertThat(rest.getForEntity("/api/admin/consent", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        Session admin = devLogin("admin");
        ResponseEntity<String> audit =
                rest.exchange("/api/admin/consent", HttpMethod.GET, admin.get(), String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.getBody())
                .contains("\"pluginId\":\"good\"")
                .contains("https://plausible.example")
                .contains("\"category\":\"necessary\"")
                .contains("\"prompted\":false");
    }

    @Test
    void theFingerprintChangesOnlyWhenTheDeclarationDoes() {
        String before = rest.getForEntity("/api/consent", String.class).getBody();
        assertThat(rest.getForEntity("/api/consent", String.class).getBody()).isEqualTo(before);

        // Switching a plugin off removes its services, so the question a visitor already answered is no
        // longer the question being asked — the shell must be able to see that.
        Session admin = devLogin("admin");
        setEnabled(admin, "good", false);
        try {
            assertThat(rest.getForEntity("/api/consent", String.class).getBody()).isNotEqualTo(before);
        } finally {
            setEnabled(admin, "good", true);
        }
        assertThat(rest.getForEntity("/api/consent", String.class).getBody()).isEqualTo(before);
    }

    @Test
    void declaredHostsWidenTheCspAndUndeclaredOnesDoNot() {
        // With consent granted for the declared category — the refusal case is covered separately.
        HttpHeaders headers = rest.exchange("/api/meta", HttpMethod.GET,
                new HttpEntity<>(cookieHeader("mc_consent=analytics")), String.class).getHeaders();
        String csp = headers.getFirst("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("script-src 'self'").contains("https://plausible.example");
        // A `necessary` service is never asked about but still loads, so its origin must be allowed too.
        assertThat(csp).contains("https://necessary.example");
        // Nothing else gets in: an origin no plugin declared stays blocked.
        assertThat(csp).doesNotContain("evil.example");
    }

    @Test
    void switchingAPluginOffWithdrawsItsConsentAskAndCspAllowance() {
        Session admin = devLogin("admin");
        try {
            setEnabled(admin, "good", false);
            assertThat(rest.getForEntity("/api/consent", String.class).getBody())
                    .doesNotContain("analytics");
            assertThat(rest.getForEntity("/api/meta", String.class)
                    .getHeaders().getFirst("Content-Security-Policy"))
                    .doesNotContain("plausible.example");
        } finally {
            setEnabled(admin, "good", true);
        }
    }

    @Test
    void aPluginCanReportAnEntryButOnlyWithinTheRules() {
        String path = "/api/plugins/good/log";
        // Anonymous is refused (CSRF-less PUT/POST never reaches the handler) — logging is a write.
        assertThat(rest.exchange(path, HttpMethod.POST, json("{\"level\":\"WARN\",\"message\":\"x\"}"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Session podcaster = devLogin("podcaster");
        assertThat(rest.exchange(path, HttpMethod.POST,
                podcaster.write("{\"level\":\"WARN\",\"message\":\"the widget could not load\"}", true),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // An unusable level or an empty message is a 400, not a silently stored entry.
        assertThat(rest.exchange(path, HttpMethod.POST,
                podcaster.write("{\"level\":\"SHOUT\",\"message\":\"x\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange(path, HttpMethod.POST,
                podcaster.write("{\"level\":\"WARN\",\"message\":\"  \"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The entry reaches the admin log, attributed to the plugin.
        Session admin = devLogin("admin");
        ResponseEntity<String> logs = rest.exchange(
                "/api/admin/logs?pluginId=good", HttpMethod.GET, admin.get(), String.class);
        assertThat(logs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logs.getBody()).contains("the widget could not load").contains("\"pluginId\":\"good\"");
    }

    @Test
    void aDisabledPluginCannotReportEntries() {
        Session admin = devLogin("admin");
        Session podcaster = devLogin("podcaster");
        try {
            setEnabled(admin, "good", false);
            assertThat(rest.exchange("/api/plugins/good/log", HttpMethod.POST,
                    podcaster.write("{\"level\":\"WARN\",\"message\":\"still here\"}", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            setEnabled(admin, "good", true);
        }
    }

    @Test
    void aRejectionIsLoggedAgainstThePluginItConcerns() {
        // Regression: the MDC tag used to be opened with try-with-resources around the try block, and Java
        // closes the resource *before* the catch runs — so the rejection, the one entry an operator actually
        // looks for, was written with no plugin attribution and could not be filtered by plugin.
        Session admin = devLogin("admin");
        ResponseEntity<String> logs = rest.exchange(
                "/api/admin/logs?pluginId=broken", HttpMethod.GET, admin.get(), String.class);
        assertThat(logs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logs.getBody())
                .contains("\"pluginId\":\"broken\"")
                .containsIgnoringCase("platformApi");
    }

    @Test
    void theHealthViewExplainsWhyEachPluginIsOrIsNotRunning() {
        Session admin = devLogin("admin");
        ResponseEntity<String> health = rest.exchange(
                "/api/admin/health", HttpMethod.GET, admin.get(), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The rejected fixtures carry their reason here, so an operator never needs the container logs.
        assertThat(health.getBody())
                .contains("\"id\":\"good\"").contains("LOADED")
                .contains("REJECTED").containsIgnoringCase("platformApi");
    }

    private void setEnabled(Session admin, String pluginId, boolean enabled) {
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/plugins/" + pluginId + "/enabled?value=" + enabled,
                HttpMethod.PUT, admin.write("", true), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String adminPlugins(Session admin) {
        return rest.exchange("/api/admin/plugins", HttpMethod.GET, admin.get(), String.class).getBody();
    }

    /** The effective value of one config field of the {@code good} plugin, as JSON text. */
    private String configValueOf(Session admin, String field) {
        String body = adminPlugins(admin);
        int fieldAt = body.indexOf("\"" + field + "\"");
        assertThat(fieldAt).isNotNegative();
        int valueAt = body.indexOf("\"value\":", fieldAt);
        assertThat(valueAt).isNotNegative();
        int from = valueAt + "\"value\":".length();
        int to = from;
        while (to < body.length() && body.charAt(to) != ',' && body.charAt(to) != '}') {
            to++;
        }
        return body.substring(from, to).trim();
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Session devLogin(String role) {
        ResponseEntity<String> response =
                rest.postForEntity("/api/auth/dev-login?role=" + role, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        return new Session(cookieValue(setCookies, "MOSAICAST_SESSION"), cookieValue(setCookies, "XSRF-TOKEN"));
    }

    private static String cookieValue(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(0, c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + name + " cookie set"));
    }

    /** A logged-in session's cookies with helpers to build authenticated requests. */
    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<Void> get() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            return new HttpEntity<>(headers);
        }

        HttpEntity<String> write(String body, boolean withCsrf) {
            HttpHeaders headers = baseHeaders(withCsrf);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
        }

        HttpEntity<Void> delete(boolean withCsrf) {
            return new HttpEntity<>(baseHeaders(withCsrf));
        }

        private HttpHeaders baseHeaders(boolean withCsrf) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            if (withCsrf) {
                headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            }
            return headers;
        }
    }
}
