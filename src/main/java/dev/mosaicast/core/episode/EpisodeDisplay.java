// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The presentation layer of an episode (ARCHITECTURE §4.2): a non-authoritative read-through snapshot
 * derived from the feed and <strong>overwritten on every fetch</strong>. Runtime/date here always come
 * from the feed; plugin metrics never mix in. Keyed 1:1 by {@link EpisodeRef} id, so it survives as the
 * feed's view while identity and plugin data live on the ref.
 */
@Entity
@Table(name = "episode_display")
public class EpisodeDisplay {

    @Id
    @Column(name = "episode_ref_id", nullable = false, updatable = false)
    private UUID episodeRefId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private DisplaySnapshot snapshot;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    protected EpisodeDisplay() {
        // for JPA
    }

    public EpisodeDisplay(UUID episodeRefId, DisplaySnapshot snapshot) {
        this.episodeRefId = episodeRefId;
        this.snapshot = snapshot;
    }

    /** Overwrites the snapshot on a fresh fetch — the feed is the source of truth for presentation. */
    public void overwrite(DisplaySnapshot snapshot) {
        this.snapshot = snapshot;
        this.fetchedAt = Instant.now();
    }

    public UUID getEpisodeRefId() {
        return episodeRefId;
    }

    public DisplaySnapshot getSnapshot() {
        return snapshot;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
