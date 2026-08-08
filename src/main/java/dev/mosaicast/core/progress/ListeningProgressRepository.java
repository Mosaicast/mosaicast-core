// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.progress;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for per-user {@link ListeningProgress}. */
public interface ListeningProgressRepository extends JpaRepository<ListeningProgress, ListeningProgress.Key> {

    /** A user's progress for a set of episodes (the shell asks for the episodes currently on screen). */
    List<ListeningProgress> findByIdUserIdAndIdEpisodeRefIdIn(UUID userId, List<UUID> episodeRefIds);

    /** Erases everything stored for one user — what switching "remember where I stopped" off has to do. */
    void deleteByIdUserId(UUID userId);
}
