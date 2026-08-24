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
 * A tag on an episode (ARCHITECTURE §6.1/§6.3) — a keyword/category the shell can filter by, and one half of
 * the topical signal behind related episodes.
 *
 * <p>The {@code tag} is the vocabulary's canonical key ({@code dev.mosaicast.core.tag.TagKeys}), not the
 * spelling whoever wrote it used; the spelling survives as the vocabulary entry's display label.
 *
 * <p><strong>{@code source} is part of the key</strong>, because the same tag can legitimately arrive from
 * more than one writer and each of them owns their own row. The feed's rows are still overwritten per poll
 * and are still non-authoritative; a podcaster's or a plugin's are not, which is the whole reason the column
 * exists — before it, the reconciler's delete-then-insert took everyone's tags with it at the next fetch.
 */
@Entity
@Table(name = "episode_tag")
public class EpisodeTag {

    @EmbeddedId
    private Key id;

    protected EpisodeTag() {
        // for JPA
    }

    public EpisodeTag(UUID episodeRefId, String tag, String source) {
        this.id = new Key(episodeRefId, tag, source);
    }

    public UUID getEpisodeRefId() {
        return id.episodeRefId();
    }

    public String getTag() {
        return id.tag();
    }

    /** Who put this tag here: {@code feed}, {@code manual}, or {@code plugin:<id>}. */
    public String getSource() {
        return id.source();
    }

    /** Composite primary key for {@link EpisodeTag}. */
    @Embeddable
    public record Key(
            @Column(name = "episode_ref_id") UUID episodeRefId,
            @Column(name = "tag") String tag,
            @Column(name = "source") String source) implements Serializable {
    }
}
