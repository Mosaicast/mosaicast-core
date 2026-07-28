// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 */
@SpringBootTest
@Testcontainers
class AppLogIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppLogIntegrationTest.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppLogService logs;

    @Autowired
    private AppLogRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void aWarningLoggedByCoreIsCaptured() {
        log.warn("something went sideways in a test");

        AppLogEntry entry = awaitOne();
        assertThat(entry.getLevel()).isEqualTo("WARN");
        assertThat(entry.getMessage()).isEqualTo("something went sideways in a test");
        // Subsystem comes from the package below dev.mosaicast.core, source from the class name.
        assertThat(entry.getSubsystem()).isEqualTo("log");
        assertThat(entry.getSource()).isEqualTo("AppLogIntegrationTest");
    }

    @Test
    void aThrowableIsKeptAsDetail() {
        log.error("boom", new IllegalStateException("the cause"));

        AppLogEntry entry = awaitOne();
        assertThat(entry.getLevel()).isEqualTo("ERROR");
        assertThat(entry.getDetail()).contains("IllegalStateException").contains("the cause");
    }

    @Test
    void mdcAttributionReachesTheColumns() {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", "acme");
                MDC.MDCCloseable alsoIgnored = MDC.putCloseable("feedId", "feed-7")) {
            log.warn("a plugin misbehaved");
        }

        AppLogEntry entry = awaitOne();
        assertThat(entry.getPluginId()).isEqualTo("acme");
        // Anything else the call site knew travels as structured context rather than needing its own column.
        assertThat(entry.getContext().get("feedId").asText()).isEqualTo("feed-7");
    }

    @Test
    void infoIsStoredSoTheContextOfAFailureSurvives() {
        // The store keeps INFO by default: the line before a failure is usually what explains it, and an
        // entry that was never stored cannot be found later. Hiding it is the viewer's job, not the store's.
        log.info("a routine step that will matter in hindsight");

        AppLogEntry entry = awaitOne();
        assertThat(entry.getLevel()).isEqualTo("INFO");
    }

    @Test
    void debugIsBelowTheDefaultThresholdAndIsNotCaptured() {
        // Default (non-dev) capture is INFO; the dev profile lowers it to DEBUG.
        log.debug("chatter nobody needs outside development");
        log.warn("the marker that proves the writer ran");

        AppLogEntry entry = awaitOne();
        assertThat(entry.getMessage()).isEqualTo("the marker that proves the writer ran");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aLevelFilterMeansThatLevelAndAbove() {
        logs.record(AppLogLevel.ERROR, "feed", "FeedPipeline", null, "an error", null, null);
        logs.record(AppLogLevel.WARN, "feed", "FeedPipeline", null, "a warning", null, null);
        logs.record(AppLogLevel.INFO, "feed", "FeedPipeline", null, "an info", null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.count() == 3);

        // A viewer filtered to WARN that hid ERRORs would be actively misleading.
        assertThat(logs.search("WARN", null, null, null, null, PageRequest.of(0, 10)).getContent())
                .extracting(AppLogEntry::getLevel).containsExactlyInAnyOrder("ERROR", "WARN");
        assertThat(logs.search("INFO", null, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
        assertThat(logs.search("ERROR", null, null, null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getLevel).isEqualTo("ERROR");
        // No level filter means every level.
        assertThat(logs.search(null, null, null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
    }

    @Test
    void filtersAndPagingNarrowTheView() {
        logs.record(AppLogLevel.ERROR, "feed", "FeedPipeline", null, "feed exploded", null, null);
        logs.record(AppLogLevel.WARN, "plugin", "frontend", "acme", "plugin grumbled", null, null);
        logs.record(AppLogLevel.WARN, "plugin", "frontend", "other", "another plugin grumbled", null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.count() == 3);

        assertThat(logs.search("ERROR", null, null, null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getMessage).isEqualTo("feed exploded");
        assertThat(logs.search(null, "plugin", null, null, null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
        assertThat(logs.search(null, null, "acme", null, null, PageRequest.of(0, 10)).getContent())
                .singleElement().extracting(AppLogEntry::getPluginId).isEqualTo("acme");
        // Free text is a case-insensitive contains over the message.
        assertThat(logs.search(null, null, null, null, "EXPLODED", PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
        // A cut-off in the future matches nothing.
        assertThat(logs.search(null, null, null, Instant.now().plusSeconds(60), null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();

        assertThat(logs.facets().subsystems()).contains("feed", "plugin");
        assertThat(logs.facets().pluginIds()).containsExactly("acme", "other");
    }

    @Test
    void retentionPrunesByAge() {
        repository.save(new AppLogEntry(Instant.now().minus(400, ChronoUnit.DAYS), AppLogLevel.WARN,
                "feed", "FeedPipeline", null, "ancient history", null, null));
        logs.record(AppLogLevel.WARN, "feed", "FeedPipeline", null, "recent enough", null, null);
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.count() == 2);

        logs.prune();

        assertThat(repository.findAll()).singleElement()
                .extracting(AppLogEntry::getMessage).isEqualTo("recent enough");
    }

    @Test
    void anOversizedMessageIsTruncatedRatherThanLost() {
        logs.record(AppLogLevel.WARN, "feed", "FeedPipeline", null, "x".repeat(5_000), null, null);

        AppLogEntry entry = awaitOne();
        assertThat(entry.getMessage()).hasSize(AppLogEntry.MAX_MESSAGE).endsWith("…");
    }

    /** Waits for the asynchronous writer to flush, then returns the single entry it wrote. */
    private AppLogEntry awaitOne() {
        await().atMost(Duration.ofSeconds(10)).until(() -> repository.count() >= 1);
        return repository.findAll().stream()
                .filter(e -> !e.getMessage().startsWith("Pruned"))
                .findFirst()
                .orElseThrow();
    }
}
