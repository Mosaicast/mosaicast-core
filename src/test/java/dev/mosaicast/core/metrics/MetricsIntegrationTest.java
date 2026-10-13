// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.siteaccess.SiteAccessRepository;
import dev.mosaicast.core.support.DevLogin;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Anonymous counting end to end (ARCHITECTURE §19, §18.5): what the beacon counts and what it drops, the
 * edge's report and its acknowledgement, the switches, who may read, and that nothing about a visitor is
 * ever stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(properties = "mosaicast.edge.token=edge-secret")
class MetricsIntegrationTest {

    private static final String BROWSER =
            "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MetricsAggregator aggregator;

    @Autowired
    private SiteConfigService site;

    @Autowired
    private SiteAccessRepository siteAccess;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private dev.mosaicast.core.siteaccess.EdgeHeartbeat heartbeat;

    /** A client address of this test's own, so one test's rate-limit window cannot reach into another's. */
    private String client;

    @BeforeEach
    void clean() {
        client = "203.0.113." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
        aggregator.flush();
        jdbc.update("delete from metric_counts");
        jdbc.update("delete from metric_gauge");
        siteAccess.deleteAll();
        heartbeat.forgetWaiting();
        site.updateCounting(true);
        if (refs.findBySlug("countcast-s01e01").isEmpty()) {
            Feed feed = feeds.save(Feed.rss("https://example.test/count.xml", "Count Cast"));
            refs.save(EpisodeRef.published(feed.getId(), "guid-count-1", 1, 1, "countcast-s01e01"));
        }
    }

    private ResponseEntity<String> beacon(String json, String userAgent, String extraHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        // core trusts X-Forwarded-For from a local proxy (the edge in production)
        headers.set("X-Forwarded-For", client);
        if (extraHeader != null) {
            headers.set(extraHeader.split(":")[0], extraHeader.split(":")[1]);
        }
        // No cookie, no CSRF token: the shell sends with credentials omitted.
        return rest.exchange("/api/metrics/events", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private long count(String source, String kind, String subject, int milestone) {
        aggregator.flush();
        Long n = jdbc.queryForObject("""
                select coalesce(sum(count), 0) from metric_counts
                where source = ? and kind = ? and subject = ? and milestone = ?""",
                Long.class, source, kind, subject, milestone);
        return n == null ? 0 : n;
    }

    @Test
    void existingSubjectsAreCountedAndEverythingElseIsDroppedSilently() {
        ResponseEntity<String> response = beacon("""
                {"events":[{"kind":"view","subject":"home"},
                           {"kind":"view","subject":"episode/countcast-s01e01"},
                           {"kind":"play","subject":"episode/countcast-s01e01"},
                           {"kind":"progress","subject":"episode/countcast-s01e01","milestone":50},
                           {"kind":"view","subject":"episode/invented-slug"},
                           {"kind":"play","subject":"home"},
                           {"kind":"progress","subject":"episode/countcast-s01e01","milestone":33},
                           {"kind":"click","subject":"home"}]}""", BROWSER, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("core", "view", "home", 0)).isEqualTo(1);
        assertThat(count("core", "view", "episode/countcast-s01e01", 0)).isEqualTo(1);
        assertThat(count("core", "play", "episode/countcast-s01e01", 0)).isEqualTo(1);
        assertThat(count("core", "progress", "episode/countcast-s01e01", 50)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from metric_counts", Long.class)).isEqualTo(4);
    }

    @Test
    void crawlersGpcAndASwitchedOffSiteCountNothing() {
        String home = "{\"events\":[{\"kind\":\"view\",\"subject\":\"home\"}]}";
        beacon(home, "WhatsApp/2.23.20.0", null);
        beacon(home, BROWSER, "Sec-GPC:1");
        site.updateCounting(false);
        beacon(home, BROWSER, null);

        assertThat(count("core", "view", "home", 0)).isZero();
    }

    @Test
    void oneClientIsCappedPerMinute() {
        StringBuilder many = new StringBuilder("{\"events\":[");
        for (int i = 0; i < MetricsController.MAX_EVENTS; i++) {
            many.append(i == 0 ? "" : ",").append("{\"kind\":\"view\",\"subject\":\"home\"}");
        }
        many.append("]}");
        for (int i = 0; i < 8; i++) {
            beacon(many.toString(), BROWSER, null);
        }
        assertThat(count("core", "view", "home", 0)).isEqualTo(MetricsController.EVENTS_PER_MINUTE);

        // the cap is per client: someone else is still counted
        client = "198.51.100.7";
        beacon("{\"events\":[{\"kind\":\"view\",\"subject\":\"home\"}]}", BROWSER, null);
        assertThat(count("core", "view", "home", 0)).isEqualTo(MetricsController.EVENTS_PER_MINUTE + 1);
    }

    @Test
    void nothingAboutAVisitorIsStored() {
        beacon("{\"events\":[{\"kind\":\"view\",\"subject\":\"home\"}]}", BROWSER, null);
        aggregator.flush();
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name in ('metric_counts', 'metric_gauge')""", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "bucket", "span", "source", "kind", "subject", "milestone", "count",
                "name", "scope", "max", "updated_at");
    }

    // ---- the edge's report (§18.5) ----

    private ResponseEntity<String> report(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Mosaicast-Edge-Token", token);
        }
        headers.set("Mosaicast-Edge-Build", "test-build");
        return rest.exchange("/api/site/edge/report", HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    private static String hour(int hoursAgo) {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(hoursAgo, ChronoUnit.HOURS).toString();
    }

    @Test
    void theEdgeReportIsStoredIdempotentlyAndAcknowledged() {
        String body = """
                {"waiting":120,"waitingCapped":false,"policyRevision":0,
                 "hours":[{"start":"%s","views":{"countdown":5,"legal":1,"sparkles":9}},
                          {"start":"%s","views":{"countdown":7}}]}""".formatted(hour(2), hour(1));

        ResponseEntity<String> first = report("edge-secret", body);
        ResponseEntity<String> again = report("edge-secret", body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"ackThrough\":\"" + hour(1).replace(":00Z", ":00Z"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        // SET semantics: the resend changed nothing; the unknown kind was ignored, missing kinds are 0.
        assertThat(count("edge", "view", "edge/countdown", 0)).isEqualTo(12);
        assertThat(count("edge", "view", "edge/legal", 0)).isEqualTo(1);
        assertThat(count("edge", "view", "edge/maintenance", 0)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from metric_counts where subject = 'edge/sparkles'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("select max from metric_gauge where scope = 'rev:0'", Long.class))
                .isEqualTo(120);
    }

    @Test
    void theReportNeedsTheTokenAndWellFormedInput() {
        String valid = "{\"waiting\":1,\"hours\":[]}";
        assertThat(report(null, valid).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(report("guess", valid).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(report("edge-secret", "not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report("edge-secret", "{\"waiting\":-1}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report("edge-secret", "{\"waiting\":1,\"hours\":[{\"start\":\"" + hour(1)
                .replace(":00:00Z", ":30:00Z") + "\",\"views\":{}}]}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report("edge-secret", "{\"waiting\":1,\"hours\":[{\"start\":\"" + hour(1)
                + "\",\"views\":{\"countdown\":1.5}}]}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report("edge-secret", "{\"waiting\":1,\"hours\":[{\"start\":\"" + hour(-2)
                + "\",\"views\":{}}]}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report("edge-secret", valid).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void edgeCountingOffAcknowledgesAndStoresNothing() {
        jdbc.update("insert into site_access (id, state, edge_counting, revision) values (1, 'OPEN', false, 1)");

        ResponseEntity<String> response = report("edge-secret", """
                {"waiting":3,"hours":[{"start":"%s","views":{"countdown":4}}]}""".formatted(hour(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ackThrough");
        assertThat(count("edge", "view", "edge/countdown", 0)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from metric_gauge", Long.class)).isZero();
        assertThat(rest.getForObject("/api/site/access", String.class)).contains("\"edgeCounting\":false");
        assertThat(heartbeat.waiting()).isNull();
    }

    // ---- reading, switching, folding ----

    @Test
    void podcastersReadAndOnlyAdminsSwitch() {
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");

        assertThat(get(podcaster, "/api/admin/metrics").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(fan, "/api/admin/metrics").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(podcaster, "/api/admin/metrics/settings", "{\"counting\":false}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(DevLogin.login(rest, "admin"), "/api/admin/metrics/settings", "{\"counting\":false}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(site.get().isCounting()).isFalse();
        assertThat(rest.getForObject("/api/meta", String.class)).contains("\"counting\":false");
    }

    @Test
    void oldHoursFoldIntoDays(@Autowired MetricsStore store) {
        Instant old = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(40, ChronoUnit.DAYS);
        for (int h = 0; h < 3; h++) {
            jdbc.update("""
                    insert into metric_counts (bucket, span, source, kind, subject, milestone, count)
                    values (?, 'hour', 'core', 'view', 'home', 0, 2)""",
                    Timestamp.from(old.plus(h, ChronoUnit.HOURS)));
        }

        assertThat(store.foldOldHours(Instant.now())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select count from metric_counts where span = 'day' and bucket = ?", Long.class,
                Timestamp.from(old))).isEqualTo(6);
        assertThat(store.foldOldHours(Instant.now())).isZero();
    }

    private ResponseEntity<String> get(DevLogin.Cookies who, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> put(DevLogin.Cookies who, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }
}
