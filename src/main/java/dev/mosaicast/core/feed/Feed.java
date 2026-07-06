// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A configured feed source (ARCHITECTURE §5). In v1 this is an RSS feed; the {@code manual} type holds
 * host-created planned episodes that have no external feed yet (§4.3). The rest of the platform queries
 * a source's {@link SourceCapabilities}, never its {@link #type} (§5.1).
 *
 * <p>The conditional-GET bookkeeping ({@link #etag}, {@link #lastModified}) lets the scheduler poll
 * politely — an unchanged feed costs a 304 (§5.4).
 */
@Entity
@Table(name = "feed")
public class Feed {

    /** Feed {@link #type} for host-created planned episodes with no external source. */
    public static final String TYPE_MANUAL = "manual";

    /** Feed {@link #type} for an RSS/Atom source parsed with Rome. */
    public static final String TYPE_RSS = "rss";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(name = "poll_interval_seconds", nullable = false)
    private long pollIntervalSeconds = Duration.ofMinutes(30).toSeconds();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column
    private String etag;

    @Column(name = "last_modified")
    private String lastModified;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "last_fetch_status")
    private String lastFetchStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Feed() {
        // for JPA
    }

    private Feed(UUID id, String type, String url, String title) {
        this.id = id;
        this.type = type;
        this.url = url;
        this.title = title;
    }

    /** Creates a new RSS feed source (status flips PLANNED→PUBLISHED once items are reconciled). */
    public static Feed rss(String url, String title) {
        return new Feed(UUID.randomUUID(), TYPE_RSS, url, title);
    }

    /** Creates the singleton manual source that holds host-created planned episodes. */
    public static Feed manual(String title) {
        return new Feed(UUID.randomUUID(), TYPE_MANUAL, null, title);
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Duration getPollInterval() {
        return Duration.ofSeconds(pollIntervalSeconds);
    }

    public void setPollInterval(Duration interval) {
        this.pollIntervalSeconds = interval.toSeconds();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public Instant getLastFetchedAt() {
        return lastFetchedAt;
    }

    public String getLastFetchStatus() {
        return lastFetchStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Records a successful fetch: refreshes conditional-GET validators and clears the failure state. */
    public void recordSuccess(String etag, String lastModified, String status) {
        this.etag = etag;
        this.lastModified = lastModified;
        this.lastFetchStatus = status;
        this.lastError = null;
        this.consecutiveFailures = 0;
        this.lastFetchedAt = Instant.now();
    }

    /** Records a failed fetch: keeps the last good validators, increments the backoff counter (§5.4). */
    public void recordFailure(String error) {
        this.lastFetchStatus = "ERROR";
        this.lastError = error;
        this.consecutiveFailures++;
        this.lastFetchedAt = Instant.now();
    }
}
