// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/**
 * A feed-derived tag on an episode (ARCHITECTURE §6.1/§6.3) — a keyword/category the shell can filter by.
 * Overwritten per poll from the feed (non-authoritative). Composite key {@code (episodeRefId, tag)}.
 */
@Entity
@Table(name = "episode_tag")
public class EpisodeTag {

    @EmbeddedId
    private Key id;

    protected EpisodeTag() {
        // for JPA
    }

    public EpisodeTag(UUID episodeRefId, String tag) {
        this.id = new Key(episodeRefId, tag);
    }

    public UUID getEpisodeRefId() {
        return id.episodeRefId();
    }

    public String getTag() {
        return id.tag();
    }

    /** Composite primary key for {@link EpisodeTag}. */
    @Embeddable
    public record Key(
            @Column(name = "episode_ref_id") UUID episodeRefId,
            @Column(name = "tag") String tag) implements Serializable {
    }
}
