// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The identity layer of an episode (ARCHITECTURE §4.1): the stable internal id every plugin references.
 * It survives feed changes — a description edit in the RSS re-writes the {@link EpisodeDisplay} snapshot,
 * never this row.
 *
 * <p>Display data lives here authoritatively <strong>only</strong> while {@link EpisodeStatus#PLANNED}
 * (the {@code provisionalDisplay}); once the real feed item binds ({@code PLANNED → PUBLISHED}) the feed
 * snapshot rules and plugin data is untouched because it hung on this id, not the feed (§4.3).
 */
@Entity
@Table(name = "episode_ref")
public class EpisodeRef {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "feed_id", nullable = false)
    private UUID feedId;

    /**
     * The stable, human-readable public identifier (§4.1) — used in URLs, the episode API and the plugin
     * contract. Minted once and never changed afterwards, so it never orphans links or plugin data.
     *
     * <p>Stability is enforced by {@link #assignSlugIfAbsent}, which refuses to overwrite a slug that is
     * already set — <em>not</em> by {@code updatable = false} on the mapping. That looks like the stronger
     * guarantee and is in fact a weaker one: it removes the column from every {@code UPDATE} Hibernate emits,
     * including the one that first fills it in on a row created before this column existed. Rows upgraded from
     * a pre-V13 schema kept {@code slug = NULL} while the backfill reported success, which cost every legacy
     * episode its URL, its API lookup, its sitemap entry and its visibility to plugins.
     */
    @Column(unique = true)
    private String slug;

    @Column(name = "external_guid")
    private String externalGuid;

    @Column
    private Integer season;

    @Column(name = "episode_no")
    private Integer episodeNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EpisodeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false)
    private AccessType accessType = AccessType.PUBLIC;

    @Column(name = "access_tier_ref")
    private String accessTierRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provisional_display")
    private DisplaySnapshot provisionalDisplay;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    protected EpisodeRef() {
        // for JPA
    }

    private EpisodeRef(UUID id, UUID feedId, EpisodeStatus status) {
        this.id = id;
        this.feedId = feedId;
        this.status = status;
    }

    /**
     * Creates a {@code PUBLISHED} ref for a freshly-seen feed item (identity + relations only; the
     * display snapshot is stored separately, §5.2 case 1).
     */
    public static EpisodeRef published(UUID feedId, String externalGuid, Integer season, Integer episodeNo,
                                       String slug) {
        EpisodeRef ref = new EpisodeRef(UUID.randomUUID(), feedId, EpisodeStatus.PUBLISHED);
        ref.externalGuid = externalGuid;
        ref.season = season;
        ref.episodeNo = episodeNo;
        ref.slug = slug;
        return ref;
    }

    /**
     * Creates a host-authored {@code PLANNED} ref (§4.3). Its {@code provisionalDisplay} is authoritative
     * until the real feed item binds.
     */
    public static EpisodeRef planned(UUID feedId, Integer season, Integer episodeNo, DisplaySnapshot provisional,
                                     String slug) {
        EpisodeRef ref = new EpisodeRef(UUID.randomUUID(), feedId, EpisodeStatus.PLANNED);
        ref.season = season;
        ref.episodeNo = episodeNo;
        ref.provisionalDisplay = provisional;
        ref.slug = slug;
        return ref;
    }

    /**
     * Binds this {@code PLANNED} ref to a real feed item: sets the guid, refreshes relations, flips to
     * {@code PUBLISHED}, and drops the now-superseded provisional display (§5.3). Plugin data is untouched.
     */
    public void bindToFeedItem(String externalGuid, Integer season, Integer episodeNo) {
        this.externalGuid = externalGuid;
        this.season = season;
        this.episodeNo = episodeNo;
        this.status = EpisodeStatus.PUBLISHED;
        this.provisionalDisplay = null;
        this.lastSeenAt = Instant.now();
    }

    /** Refreshes the season/episode relations and last-seen marker for a known, still-present item (§5.2 case 2). */
    public void refreshFromFeed(Integer season, Integer episodeNo) {
        this.season = season;
        this.episodeNo = episodeNo;
        if (this.status == EpisodeStatus.WITHDRAWN) {
            // Re-appeared in the feed after having vanished — revive it.
            this.status = EpisodeStatus.PUBLISHED;
        }
        this.lastSeenAt = Instant.now();
    }

    /** Marks an item that vanished from the feed as {@code WITHDRAWN} — never hard-deleted (§5.2 case 3). */
    public void withdraw() {
        this.status = EpisodeStatus.WITHDRAWN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFeedId() {
        return feedId;
    }

    public String getSlug() {
        return slug;
    }

    /**
     * One-time slug assignment for legacy rows created before slugs existed (the boot-time backfill). Never
     * overwrites an existing slug — the identifier is immutable once set.
     */
    public void assignSlugIfAbsent(String slug) {
        if (this.slug == null) {
            this.slug = slug;
        }
    }

    public String getExternalGuid() {
        return externalGuid;
    }

    public Integer getSeason() {
        return season;
    }

    public Integer getEpisodeNo() {
        return episodeNo;
    }

    public EpisodeStatus getStatus() {
        return status;
    }

    public AccessType getAccessType() {
        return accessType;
    }

    public String getAccessTierRef() {
        return accessTierRef;
    }

    /** The host-authored display, with {@code descriptionText} filled in for a row stored before SDK 0.16.0. */
    public DisplaySnapshot getProvisionalDisplay() {
        return ShowNotes.complete(provisionalDisplay);
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
