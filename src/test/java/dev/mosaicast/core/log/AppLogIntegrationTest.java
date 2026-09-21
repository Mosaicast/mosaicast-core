// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The operational log end-to-end (ARCHITECTURE §13): a plain {@code log.warn} in core reaches the table via
 * the Logback appender, carries its subsystem/source/MDC attribution, and is filterable and prunable — which
 * is the whole promise of the admin viewer.
 *
 * <p><strong>Every assertion here is scoped to the rows this class wrote</strong>, never to the table as a
 * whole. The appender captures INFO by default and the running context keeps logging on its own writer
 * thread, so the table is shared with whatever the application happens to say while a test runs — and
 * because persistence is asynchronous and batched, {@code deleteAll()} in {@link #clean()} is not a fence:
 * a line logged before it can still land after it. An assertion over the whole table is therefore a race
 * whose outcome depends on which test ran first (core#189). The subsystems below are deliberately spelled
 * {@code test-*} so they cannot collide with a subsystem production code uses.
 */
@SpringBootTest
@Testcontainers
class AppLogIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppLogIntegrationTest.class);

    /** Subsystems no production code writes, so "the rows this test wrote" is exactly expressible. */
    private static final String FEED = "test-feed";
    private static final String PLUGIN = "test-plugin";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppLogService logs;

    @Autowired
    private AppLogRepository repository;

    @BeforeEach
    void clean() {
        // Hygiene between tests, not a fence — see the class comment. What makes the assertions
        // deterministic is that each one names the rows it is about.
        repository.deleteAll();
    }

    @Test
    void aWarningLoggedByCoreIsCaptured() {
        log.warn("something went sideways in a test");

        AppLogEntry entry = awaitMessage("something went sideways in a test");
        assertThat(entry.getLevel()).isEqualTo("WARN");
        // Subsystem comes from the package below dev.mosaicast.core, source from the class name.
        assertThat(entry.getSubsystem()).isEqualTo("log");
        assertThat(entry.getSource()).isEqualTo("AppLogIntegrationTest");
    }

    @Test
    void aThrowableIsKeptAsDetail() {
        log.error("boom", new IllegalStateException("the cause"));

        AppLogEntry entry = awaitMessage("boom");
        assertThat(entry.getLevel()).isEqualTo("ERROR");
        assertThat(entry.getDetail()).contains("IllegalStateException").contains("the cause");
    }

    @Test
    void mdcAttributionReachesTheColumns() {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", "acme");
                MDC.MDCCloseable alsoIgnored = MDC.putCloseable("feedId", "feed-7")) {
            log.warn("a plugin misbehaved");
        }

        AppLogEntry entry = awaitMessage("a plugin misbehaved");
        assertThat(entry.getPluginId()).isEqualTo("acme");
        // Anything else the call site knew travels as structured context rather than needing its own column.
        assertThat(entry.getContext().get("feedId").asText()).isEqualTo("feed-7");
    }

    @Test
    void infoIsStoredSoTheContextOfAFailureSurvives() {
        // The store keeps INFO by default: the line before a failure is usually what explains it, and an
        // entry that was never stored cannot be found later. Hiding it is the viewer's job, not the store's.
        log.info("a routine step that will matter in hindsight");

        AppLogEntry entry = awaitMessage("a routine step that will matter in hindsight");
        assertThat(entry.getLevel()).isEqualTo("INFO");
    }

    @Test
    void debugIsBelowTheDefaultThresholdAndIsNotCaptured() {
        // Default (non-dev) capture is INFO; the dev profile lowers it to DEBUG.
        log.debug("chatter nobody needs outside development");
        log.warn("the marker that proves the writer ran");

        // The marker is the barrier: once it is stored, the DEBUG line that preceded it has had its chance.
        awaitMessage("the marker that proves the writer ran");
        // Asking whether *this* line was stored is the claim under test. Counting the table instead once
        // made the outcome depend on whatever else the context logged (core#189), and a count can only ever
        // say that something is there, not that the DEBUG line is the thing that is missing.
        assertThat(findMessage("chatter nobody needs outside development")).isEmpty();
    }

    @Test
    void aLevelFilterMeansThatLevelAndAbove() {
        logs.record(AppLogLevel.ERROR, FEED, "FeedPipeline", null, "an error", null, null);
        logs.record(AppLogLevel.WARN, FEED, "FeedPipeline", null, "a warning", null, null);
        logs.record(AppLogLevel.INFO, FEED, "FeedPipeline", null, "an info", null, null);
        awaitRows(FEED, 3);

        // A viewer filtered to WARN that hid ERRORs would be actively misleading.
        assertThat(logs.search("WARN", FEED, null, null, null, PageRequest.of(0, 10)).getContent())
                .extracting(AppLogEntry::getLevel).containsExactlyInAnyOrder("ERROR", "WARN");
        assertThat(logs.search("INFO", FEED, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
        assertThat(logs.search("ERROR", FEED, null, null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getLevel).isEqualTo("ERROR");
        // No level filter means every level.
        assertThat(logs.search(null, FEED, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
    }

    @Test
    void filtersAndPagingNarrowTheView() {
        logs.record(AppLogLevel.ERROR, FEED, "FeedPipeline", null, "feed exploded", null, null);
        logs.record(AppLogLevel.WARN, PLUGIN, "frontend", "acme", "plugin grumbled", null, null);
        logs.record(AppLogLevel.WARN, PLUGIN, "frontend", "other", "another plugin grumbled", null, null);
        awaitRows(FEED, 1);
        awaitRows(PLUGIN, 2);

        assertThat(logs.search("ERROR", FEED, null, null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getMessage).isEqualTo("feed exploded");
        assertThat(logs.search(null, PLUGIN, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
        assertThat(logs.search(null, PLUGIN, "acme", null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getPluginId).isEqualTo("acme");
        // Free text is a case-insensitive contains over the message.
        assertThat(logs.search(null, FEED, null, null, "EXPLODED", PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
        // A cut-off in the future matches nothing.
        assertThat(logs.search(null, null, null, Instant.now().plusSeconds(60), null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();

        // The facets read the whole table by definition, so these are "contains", not "exactly": the
        // context may legitimately have logged something of its own while this test ran.
        assertThat(logs.facets().subsystems()).contains(FEED, PLUGIN);
        assertThat(logs.facets().pluginIds()).contains("acme", "other");
    }

    @Test
    void retentionPrunesByAge() {
        repository.save(new AppLogEntry(Instant.now().minus(400, ChronoUnit.DAYS), AppLogLevel.WARN,
                FEED, "FeedPipeline", null, "ancient history", null, null));
        logs.record(AppLogLevel.WARN, FEED, "FeedPipeline", null, "recent enough", null, null);
        awaitRows(FEED, 2);

        logs.prune();

        assertThat(rowsOf(FEED)).singleElement()
                .extracting(AppLogEntry::getMessage).isEqualTo("recent enough");
    }

    @Test
    void anOversizedMessageIsTruncatedRatherThanLost() {
        logs.record(AppLogLevel.WARN, FEED, "FeedPipeline", null, "x".repeat(5_000), null, null);

        AppLogEntry entry = awaitEntry(e -> FEED.equals(e.getSubsystem()) && e.getMessage().startsWith("xxx"));
        assertThat(entry.getMessage()).hasSize(AppLogEntry.MAX_MESSAGE).endsWith("…");
    }

    /** Waits for the asynchronous writer to flush the entry carrying exactly this message, and returns it. */
    private AppLogEntry awaitMessage(String message) {
        await().atMost(Duration.ofSeconds(10)).until(() -> findMessage(message).isPresent());
        return findMessage(message).orElseThrow();
    }

    /** Waits for the first entry matching {@code match} — for messages the store rewrites, such as a truncation. */
    private AppLogEntry awaitEntry(Predicate<AppLogEntry> match) {
        await().atMost(Duration.ofSeconds(10)).until(() -> firstMatching(match).isPresent());
        return firstMatching(match).orElseThrow();
    }

    /** Waits until the writer has persisted exactly {@code expected} rows for one of this class's subsystems. */
    private void awaitRows(String subsystem, int expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> rowsOf(subsystem).size() == expected);
    }

    private Optional<AppLogEntry> findMessage(String message) {
        return firstMatching(e -> message.equals(e.getMessage()));
    }

    private Optional<AppLogEntry> firstMatching(Predicate<AppLogEntry> match) {
        return repository.findAll().stream().filter(match).findFirst();
    }

    /** Only the rows written under one of this class's own subsystems. */
    private List<AppLogEntry> rowsOf(String subsystem) {
        return repository.findAll().stream()
                .filter(e -> subsystem.equals(e.getSubsystem()))
                .toList();
    }
}
