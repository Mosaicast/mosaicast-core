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
 * A podcaster-curated related episode (ARCHITECTURE §6.3): "under episode A, show episode B".
 *
 * <p>Pins win over everything the strategy computes. The derived signals — same season, shared tags, fuzzy
 * title — are good at the common case and blind to the ones a human sees instantly (a two-part story split
 * across seasons, a callback to an episode three years earlier). This is where that knowledge goes.
 *
 * <p><strong>Directed, not symmetric.</strong> Pinning B under A does not pin A under B: a trailer belongs
 * under every episode of its season without every episode belonging under the trailer. Where the relation
 * really is mutual, the podcaster pins both ways, and that is two decisions rather than one guessed.
 *
 * <p>Unlike {@link EpisodeTag}, this is <strong>authoritative</strong> — it is entered by a person, not
 * derived from a feed, so a poll never overwrites it.
 */
@Entity
@Table(name = "episode_pin")
public class EpisodePin {

    @EmbeddedId
    private Key id;

    /** Display order under the episode; lower first. Ties fall back to insertion-independent ordering. */
    @Column(name = "position", nullable = false)
    private int position;

    protected EpisodePin() {
        // for JPA
    }

    public EpisodePin(UUID episodeRefId, UUID relatedRefId, int position) {
        this.id = new Key(episodeRefId, relatedRefId);
        this.position = position;
    }

    public UUID getEpisodeRefId() {
        return id.episodeRefId();
    }

    public UUID getRelatedRefId() {
        return id.relatedRefId();
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    /** Composite primary key: one pin per (episode, related) pair. */
    @Embeddable
    public record Key(
            @Column(name = "episode_ref_id") UUID episodeRefId,
            @Column(name = "related_ref_id") UUID relatedRefId) implements Serializable {
    }
}
