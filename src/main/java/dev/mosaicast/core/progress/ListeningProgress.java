// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's resume position for one episode (ARCHITECTURE §6.5) — the core listening-progress service for
 * logged-in users. Composite key {@code (userId, episodeRefId)}; overwritten as playback advances.
 */
@Entity
@Table(name = "listening_progress")
public class ListeningProgress {

    @EmbeddedId
    private Key id;

    @Column(name = "position_seconds", nullable = false)
    private int positionSeconds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ListeningProgress() {
        // for JPA
    }

    public ListeningProgress(UUID userId, UUID episodeRefId, int positionSeconds) {
        this.id = new Key(userId, episodeRefId);
        this.positionSeconds = positionSeconds;
    }

    public UUID getEpisodeRefId() {
        return id.episodeRefId();
    }

    public int getPositionSeconds() {
        return positionSeconds;
    }

    /** Overwrites the position on a fresh update. */
    public void update(int positionSeconds) {
        this.positionSeconds = positionSeconds;
        this.updatedAt = Instant.now();
    }

    /** Composite primary key for {@link ListeningProgress}. */
    @Embeddable
    public record Key(
            @Column(name = "user_id") UUID userId,
            @Column(name = "episode_ref_id") UUID episodeRefId) implements Serializable {
    }
}
