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
 * A configured feed source (ARCHITECTURE §5). In v1 this is an RSS feed. The rest of the platform queries
 * a source's {@link SourceCapabilities}, never its {@link #type} (§5.1). Host-created planned episodes
 * (§4.3) attach to their target feed directly — there is no separate "manual" source.
 *
 * <p>The conditional-GET bookkeeping ({@link #etag}, {@link #lastModified}) lets the scheduler poll
 * politely — an unchanged feed costs a 304 (§5.4).
 */
@Entity
@Table(name = "feed")
public class Feed {

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

    /**
     * The public, human-readable identifier used in URLs, the feed API and the plugin contract. Minted once
     * at creation and never changed: the title comes from the feed and can move on any poll, so re-slugging
     * would break shared links and orphan plugin data partitioned under the old scope id.
     */
    @Column(unique = true)
    private String slug;

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

    // Channel-level presentation, refreshed from the feed on each changed poll (§6.1 feed panel).
    @Column(name = "image_url")
    private String imageUrl;

    @Column
    private String author;

    @Column(columnDefinition = "text")
    private String description;

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

    public String getSlug() {
        return slug;
    }

    /** Assigns the slug if this feed has none — mints once, and re-running is a no-op (see {@link #slug}). */
    public void assignSlugIfAbsent(String slug) {
        if (this.slug == null) {
            this.slug = slug;
        }
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    /** Refreshes the channel-level presentation from a poll (§6.1); each is left unchanged when absent. */
    public void updateChannelMeta(String imageUrl, String author, String description) {
        if (imageUrl != null) {
            this.imageUrl = imageUrl;
        }
        if (author != null) {
            this.author = author;
        }
        if (description != null) {
            this.description = description;
        }
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
