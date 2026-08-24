// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.util.UUID;
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
 * good fixture plugin, a schema-declaring one, and deliberately-broken ones (incompatible
 * {@code platformApi}; a {@code schema} declaring no entities; malformed {@code backendOwned}).
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

    @Autowired
    private dev.mosaicast.core.feed.FeedRepository feeds;

    @Autowired
    private dev.mosaicast.core.episode.EpisodeRefRepository refs;

    /** The plugin's own store, for restoring what {@code register(ctx)} seeded — see the purge test. */
    @Autowired
    private PluginDataService pluginData;

    /**
     * A real episode to scope doc-store calls against.
     *
     * <p>The doc store refuses a scope that names nothing, so these tests can no longer invent a slug. That is
     * the point of the check: a plugin's UI is always mounted on a scope the host itself resolved, so only a
     * test or an attacker addresses an episode that does not exist.
     */
    private String realEpisodeSlug() {
        return refs.findAll().stream().findFirst().map(ref -> ref.getSlug()).orElseGet(() -> {
            var feed = feeds.save(dev.mosaicast.core.feed.Feed.rss("https://example.test/plugins.xml", "Plugin Cast"));
            var ref = refs.save(dev.mosaicast.core.episode.EpisodeRef.published(
                    feed.getId(), "guid-plugin-1", 1, 1, "plugin-cast-s01e01"));
            return ref.getSlug();
        });
    }

    @Test
    void goodPluginIsInPublicManifestAndBrokenOnesAreNot() {
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manifest.getBody()).contains("\"id\":\"good\"");
        assertThat(manifest.getBody()).doesNotContain("\"broken\"").doesNotContain("\"schema\"")
                .doesNotContain("\"ownedbad\"");
    }

    @Test
    void declaredCreditReachesAnonymousVisitorsAndPluginsWithoutItStillLoad() {
        // The whole path, unauthenticated: a manifest on disk -> Jackson -> PublicPlugin -> JSON. What the
        // About page shows a visitor is what this install runs and under what terms, which is not privileged.
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);

        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manifest.getBody())
                .contains("\"license\":\"Apache-2.0\"")
                .contains("\"author\":\"The Mosaicast Authors\"")
                .contains("\"homepage\":\"https://fixture.example/good\"")
                .contains("\"attribution\":\"https://fixture.example/thanks\"");

        // And the other half of the contract: `nopage` declares none of it and is still a loaded plugin.
        // A release that only added a line to an About page must not disable anybody's plugin.
        assertThat(manifest.getBody()).contains("\"id\":\"nopage\"");
    }

    @Test
    void navigationIsAnonymousAndFilteredToTheCaller() {
        // The whole path: a manifest on disk -> resolution -> JSON, with the role floor applied on the
        // server. An anonymous visitor is never told a podcaster-only entrance exists — filtering it in the
        // browser would put it in the page source of someone who may not have it.
        ResponseEntity<String> anonymous = rest.getForEntity("/api/plugins/navigation", String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getBody()).contains("\"label\":\"Good Fixture\"").contains("\"href\":\"/p/good\"");
        assertThat(anonymous.getBody()).doesNotContain("Staff only");

        Session podcaster = devLogin("podcaster");
        String forStaff = rest.exchange("/api/plugins/navigation", HttpMethod.GET, podcaster.get(), String.class)
                .getBody();
        assertThat(forStaff).contains("Staff only").contains("\"href\":\"/p/good/_secret\"");

        // A plugin with no page contributes nothing, and a rejected one is not there to contribute.
        assertThat(anonymous.getBody()).doesNotContain("\"pluginId\":\"broken\"");
    }

    @Test
    void anAdminCanHideAndReorderNavigationEntries() {
        Session admin = devLogin("admin");

        String before = rest.exchange("/api/admin/navigation", HttpMethod.GET, admin.get(), String.class).getBody();
        // The admin view shows every declared entry, including the one an anonymous visitor cannot see.
        assertThat(before).contains("Good Fixture").contains("Staff only");

        // Hide the root entry and push the other to the front. A stale decision for an entry nobody
        // declares is accepted and stored — a plugin may be mid-upgrade — and simply resolves to nothing.
        String body = """
                [{"pluginId":"good","path":"","enabled":false,"order":5},
                 {"pluginId":"good","path":"_secret","enabled":true,"order":1},
                 {"pluginId":"good","path":"_gone","enabled":true,"order":0}]
                """;
        ResponseEntity<String> saved = rest.exchange(
                "/api/admin/navigation", HttpMethod.PUT, admin.write(body, true), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        String anonymous = rest.getForEntity("/api/plugins/navigation", String.class).getBody();
        assertThat(anonymous).doesNotContain("Good Fixture");
        assertThat(anonymous).doesNotContain("_gone");

        String forStaff = rest.exchange("/api/plugins/navigation", HttpMethod.GET,
                devLogin("podcaster").get(), String.class).getBody();
        assertThat(forStaff).contains("Staff only").doesNotContain("Good Fixture");
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
        // Each rejection carries a reason (platformApi mismatch / an empty schema declaration /
        // malformed backendOwned).
        assertThat(body).containsIgnoringCase("platformApi");
        assertThat(body).containsIgnoringCase("schema");
        assertThat(body).containsIgnoringCase("backendOwned");
    }

    @Test
    void aMalformedBackendOwnedDeclarationRejectsThePluginWithAReason() {
        // `backendOwned: ["a*b"]` — a star in the middle, which the grammar does not admit. Dropping the
        // entry would be worse than any of the alternatives: the plugin would load, its manifest would say a
        // key belongs to its backend, and the host would enforce nothing. So the whole plugin is refused,
        // like any other bad manifest, and the reason names the field so the author can find it.
        Session admin = devLogin("admin");
        assertThat(adminPlugins(admin)).contains("\"id\":\"ownedbad\"").containsIgnoringCase("backendOwned");
        assertThat(rest.getForEntity("/api/plugins/ownedbad/data/site/main/x", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void registerRanAndSeededDocStore() {
        // FixturePlugin.register put a site-scoped greeting + episode-count; both read back publicly.
        // `episode-count` is also declared `backendOwned`, so this doubles as the evidence that a
        // declaration leaves the backend's own writes and every client read exactly as they were.
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
        String path = "/api/plugins/good/data/episode/" + realEpisodeSlug() + "/note";
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
    void oneUserCannotReachAnotherUsersPartition() {
        // The white-box audit's strongest finding, closed. scopeType/scopeId/key were all client input and
        // the only gate was a per-plugin role floor, so any caller above that floor could read, overwrite or
        // delete another user's key — and with no user-level scope, the SDK told authors to put the user id
        // *in the key*, where the host could not check it.
        Session fan = devLogin("fan");
        Session podcaster = devLogin("podcaster");
        String path = "/api/plugins/good/data/user/me/mark";

        assertThat(rest.exchange(path, HttpMethod.PUT, fan.write("{\"cell\":\"c3\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The same URL, a different session — and therefore a different partition. Not forbidden: absent.
        // There is no request either of them can make that names the other's data.
        assertThat(rest.exchange(path, HttpMethod.GET, fan.get(), String.class).getBody())
                .contains("c3");
        assertThat(rest.exchange(path, HttpMethod.GET, podcaster.get(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Listing is the other half: the audit noted `?prefix=` meant nothing had to be guessed.
        assertThat(rest.exchange("/api/plugins/good/data/user/me?prefix=", HttpMethod.GET,
                podcaster.get(), String.class).getBody()).doesNotContain("c3");
    }

    @Test
    void namingAUserOtherThanMeIsRefusedRatherThanQuietlyRedirected() {
        // A silent substitution would let a plugin ship code that reads as though it addresses a specific
        // person and behaves as though it does not — surfacing years later as "why is everyone seeing the
        // same board".
        Session fan = devLogin("fan");
        for (String id : new String[] {UUID.randomUUID().toString(), "someone-else", "ME", ""}) {
            String path = "/api/plugins/good/data/user/" + (id.isEmpty() ? "%20" : id) + "/mark";
            assertThat(rest.exchange(path, HttpMethod.GET, fan.get(), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void theUserScopeNeedsASessionAndIgnoresTheDeclaredFloors() {
        // Anonymous: no session, so no partition to resolve — 401 whatever the plugin declared.
        assertThat(rest.getForEntity("/api/plugins/good/data/user/me/mark", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And neither floor applies (§7.6). The fixture declares writableBy: podcaster for its shared
        // scopes, yet a fan writes their own partition — which is the case the scope exists for. Gating it
        // would force the plugin to open its shared scopes to fan writes to make its own feature work.
        Session fan = devLogin("fan");
        assertThat(rest.exchange("/api/plugins/good/data/site/main/shared", HttpMethod.PUT,
                fan.write("{\"x\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/plugins/good/data/user/me/own", HttpMethod.PUT,
                fan.write("{\"x\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- data.backendOwned: the keys a plugin's backend authors (§7.2/§7.6) ---

    @Test
    void aBackendOwnedKeyIsReadableByAClientButWritableOnlyByTheBackend() {
        // The audit's demonstration, in the host's own test: a podcaster PUT a forged site-wide aggregate
        // over the value the plugin had computed, it was served to every visitor, and then they deleted it.
        // The floors were not misconfigured — there was simply no way to say "this key is the backend's".
        Session podcaster = devLogin("podcaster");
        String path = "/api/plugins/good/data/site/main/episode-count";

        assertThat(rest.exchange(path, HttpMethod.PUT, podcaster.write("{\"forged\":9999}", true),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(path, HttpMethod.DELETE, podcaster.delete(true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Both refusals changed nothing: the backend's value is still there and still world-readable.
        ResponseEntity<String> read = rest.getForEntity(path, String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().trim()).isEqualTo("0");
    }

    @Test
    void theTwoWriteRefusalsSayWhichRuleRefused() {
        // The plugin contract promises these are distinguishable: the fixes are opposite (raise the floor,
        // or stop writing the key from the client), so an author who cannot tell them apart is stuck.
        Session fan = devLogin("fan");
        Session podcaster = devLogin("podcaster");

        ResponseEntity<String> belowFloor = rest.exchange("/api/plugins/good/data/site/main/shared",
                HttpMethod.PUT, fan.write("{\"x\":1}", true), String.class);
        ResponseEntity<String> reserved = rest.exchange("/api/plugins/good/data/site/main/episode-count",
                HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class);

        assertThat(belowFloor.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The stable machine-readable half — what a plugin's test should key on.
        assertThat(belowFloor.getBody()).contains("problems/forbidden");
        assertThat(reserved.getBody()).contains("problems/backend-owned-key");
        // And the English half, which names the manifest field that refused.
        assertThat(reserved.getBody()).contains("backendOwned").contains("episode-count");
    }

    @Test
    void aBackendOwnedPrefixCoversTheKeysUnderItAndNoOthers() {
        Session podcaster = devLogin("podcaster");
        String prefix = "/api/plugins/good/data/site/main/agg:total";
        String neighbour = "/api/plugins/good/data/site/main/aggregate";

        assertThat(rest.exchange(prefix, HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // `agg:*` reserves the keys under `agg:`, not every key that happens to start with those letters.
        assertThat(rest.exchange(neighbour, HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aBackendOwnedKeyIsStillTheOwnersInTheirOwnPartition() {
        // Same key name, reserved at shared scope, exempt in a USER partition — a backend cannot write one
        // at all, so reserving it would reserve it for nobody and lock its owner out of their own data.
        Session fan = devLogin("fan");
        String path = "/api/plugins/good/data/user/me/episode-count";

        assertThat(rest.exchange(path, HttpMethod.PUT, fan.write("{\"mine\":true}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(path, HttpMethod.GET, fan.get(), String.class).getBody())
                .contains("mine");
    }

    @Test
    void aRolefloorRefusalStillWinsOverABackendOwnedOne() {
        // Ordering, and it is a disclosure decision rather than a stylistic one: `data.backendOwned` is not
        // in the public manifest, so a caller below the floor has no business learning which keys it names.
        Session fan = devLogin("fan");
        ResponseEntity<String> response = rest.exchange("/api/plugins/good/data/site/main/episode-count",
                HttpMethod.PUT, fan.write("{\"x\":1}", true), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("problems/forbidden")
                .doesNotContain("backend-owned-key").doesNotContain("backendOwned");
    }

    @Test
    void aScopeThatNamesNothingIsNotAFreshPartition() {
        // scopeId came straight off the request path into the doc store's primary key, so any string at all
        // opened a partition: invisible to every admin surface, unbounded in number, and never corresponding
        // to a feed, season or episode that exists.
        Session podcaster = devLogin("podcaster");
        for (String scope : new String[] {
            "episode/no-such-episode",
            "feed/no-such-feed",
            "season/no-such-feed:3",
            "episode/" + "x".repeat(300),
        }) {
            String path = "/api/plugins/good/data/" + scope + "/probe";
            assertThat(rest.exchange(path, HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        // The site scope is a singleton and always resolves, and a real episode still works.
        assertThat(rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/plugins/good/data/episode/" + realEpisodeSlug() + "/ok",
                HttpMethod.PUT, podcaster.write("{\"x\":1}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
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
    void podcasterNeverReadsBackAnAdminOnlyConfigValue() {
        // Writing was always gated; reading was not. `/config` is open to PODCASTER so per-field delegation
        // works, and the PUT response was built from *every* declared field with its current value — so a
        // podcaster who submitted a field they owned (or an empty body, which validates vacuously) received
        // every admin-only secret in the 200 alongside it.
        Session admin = devLogin("admin");
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"apiToken\":\"super-secret-token\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Session podcaster = devLogin("podcaster");
        for (String body : new String[] {"{}", "{\"refreshIntervalMinutes\":20}"}) {
            ResponseEntity<String> response = rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                    podcaster.write(body, true), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).doesNotContain("super-secret-token");
            // The field itself still appears, so the form can render a row and say who owns it.
            assertThat(response.getBody()).contains("apiToken").contains("refreshIntervalMinutes");
        }

        // The role that already sees everything loses nothing.
        assertThat(rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{}", true), String.class).getBody())
                .contains("super-secret-token");

        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT,
                admin.write("{\"apiToken\":null,\"refreshIntervalMinutes\":null}", true), String.class);
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
        // restart would re-run register(). Through the plugin's own store, not over HTTP — `episode-count`
        // is declared backendOwned, so a client PUT of it is now a 403 (which is the point, and is asserted
        // in aBackendOwnedKeyIsReadableByAClientButWritableOnlyByTheBackend). This is what the SDK means when
        // it says to write your computed keys in register() as well as on a schedule.
        dev.mosaicast.plugin.api.DocStore store = new DocStoreImpl("good", pluginData);
        store.put(dev.mosaicast.plugin.api.Scope.site(), "greeting", "hello from fixture");
        store.put(dev.mosaicast.plugin.api.Scope.site(), "episode-count", 0);
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
        // A route the plugin *does* render but has no share metadata for: 200, with the site's own tags.
        // The two questions are deliberately separate — a search result page is a working route that should
        // not claim to be a shareable document, so "no OpenGraph" must not mean "no page" (§6.6).
        ResponseEntity<String> other = rest.getForEntity("/p/good/known", String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getBody()).contains("og:title").doesNotContain("Fixture shared page");
    }

    @Test
    void aSubpathThePluginDoesNotRenderIsARealNotFound() {
        // The soft-404 this closes: every subpath under a page plugin answered 200, so a crawler indexed a
        // wiki's typos and its deleted pages, and the sitemap and the status line disagreed about what
        // exists. Only the plugin can answer, and now it is asked (PageRouteProvider, platformApi 0.9.1).
        assertThat(rest.getForEntity("/p/good/nowhere", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The plugin root and a route it claims are unaffected.
        assertThat(rest.getForEntity("/p/good", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/p/good/known", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aProviderThatThrowsCostsItsOwnAnswerAndNotThePage() {
        // The fixture throws for this subpath. A broken provider must not be able to turn a plugin's
        // working pages into 404s, so the failure is logged and the route answers as it did before.
        assertThat(rest.getForEntity("/p/good/boom", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
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
    void anExtensionPointRunsOnTheSameInstanceThatWasRegistered() {
        // FixturePlugin implements both PluginBackend and SitemapProvider and stores its context in a plain
        // instance field, contributing this URL only if register(ctx) ran on the same object.
        //
        // PF4J's default ExtensionFactory builds a fresh instance per extension-point lookup, so the field
        // was null here and the entry vanished with no error anywhere — a plugin's sitemap URLs and its OG
        // tags silently missing. The host now installs SingletonExtensionFactory
        // (MosaicastPluginManager.createExtensionFactory); this is the regression test for it.
        assertThat(rest.getForEntity("/sitemap.xml", String.class).getBody())
                .contains("/p/good/ctx-seen");
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
        // The fixture also declares a service as `necessary`, and no admin has approved that claim — so it is
        // prompted like anything else and a refusal keeps its origin out too. Approving is covered below.
        assertThat(refusedCsp).doesNotContain("https://necessary.example");
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
                // Declared, not yet ruled on — so a visitor is still asked, and the operator can see why.
                .contains("\"claimsNecessary\":true")
                .contains("\"necessaryApproved\":false");
    }

    @Test
    void anAdminApprovingANecessaryClaimIsWhatMakesItUnconditional() {
        // The whole point of the gate: until an admin rules on it, a plugin asserting `necessary` gets no more
        // than any other category. Approving is the operator accepting that this loads for every visitor
        // without being asked — a judgement about their jurisdiction, not the plugin author's to make.
        Session admin = devLogin("admin");
        String path = "/api/admin/consent/necessary/good/session-keeper";

        try {
            assertThat(rest.exchange(path, HttpMethod.POST, admin.write("", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);

            // Now it is out of the toggle list and in every policy, cookie or no cookie.
            assertThat(rest.getForEntity("/api/consent", String.class).getBody())
                    .contains("\"necessaryServices\"").contains("Fixture Session Keeper");
            assertThat(rest.getForEntity("/api/meta", String.class)
                    .getHeaders().getFirst("Content-Security-Policy"))
                    .contains("https://necessary.example");

            // Only an admin may do it.
            assertThat(rest.exchange(path, HttpMethod.POST, devLogin("podcaster").write("", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(rest.exchange(path, HttpMethod.POST, json(""), String.class)
                    .getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

            // A claim on a service that does not exist is a 404, not a stored approval for nothing.
            assertThat(rest.exchange("/api/admin/consent/necessary/good/no-such-service", HttpMethod.POST,
                    admin.write("", true), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            rest.exchange(path, HttpMethod.DELETE, admin.delete(true), String.class);
        }

        // Revoked: prompted again from the next request.
        assertThat(rest.getForEntity("/api/meta", String.class)
                .getHeaders().getFirst("Content-Security-Policy"))
                .doesNotContain("https://necessary.example");
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
        // The fixture's unapproved `necessary` claim is prompted, and this cookie granted only `analytics`.
        assertThat(csp).doesNotContain("https://necessary.example");
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
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
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
