// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.progress;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side listening progress for the current user (ARCHITECTURE §6.5): the player reads resume
 * positions for the episodes on screen and writes the position as playback advances. Anonymous users keep
 * progress in localStorage only (no calls here — {@code /api/me/**} requires authentication).
 */
@RestController
@RequestMapping("/api/me/progress")
public class ProgressController {

    private final ListeningProgressRepository progress;
    private final dev.mosaicast.core.episode.EpisodeRefRepository episodes;

    public ProgressController(ListeningProgressRepository progress,
                              dev.mosaicast.core.episode.EpisodeRefRepository episodes) {
        this.episodes = episodes;
        this.progress = progress;
    }

    /** Resume positions (seconds) for the requested episodes, as {@code {episodeId: seconds}} (missing = none). */
    @GetMapping
    @Transactional(readOnly = true)
    public Map<UUID, Integer> get(
            @RequestParam(name = "episodeIds", required = false) List<UUID> episodeIds,
            Authentication authentication) {
        UUID userId = currentUserId(authentication);
        if (episodeIds == null || episodeIds.isEmpty()) {
            return Map.of();
        }
        return progress.findByIdUserIdAndIdEpisodeRefIdIn(userId, episodeIds).stream()
                .collect(Collectors.toMap(ListeningProgress::getEpisodeRefId, ListeningProgress::getPositionSeconds));
    }

    /**
     * Erases every stored position for the calling user.
     *
     * <p>This is what "remember where I stopped" being switched off has to mean. The shell cleared its own
     * {@code mc.progress.*} keys and stopped there, because there was nothing to call — so a signed-in
     * listener who turned the setting off kept a server-side history of what they had listened to and how far,
     * indefinitely, while the settings page told them the positions were deleted. Local-only erasure is not
     * erasure when the data was also sent somewhere.
     *
     * <p>Idempotent, and scoped to the session's own user id like every other method here — never to an id
     * from the request.
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> deleteAll(Authentication authentication) {
        progress.deleteByIdUserId(currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    /** Upserts the resume position for one episode. */
    @PutMapping("/{episodeId}")
    @Transactional
    public ResponseEntity<Void> put(
            @PathVariable UUID episodeId, @RequestBody UpdatePosition body, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        ListeningProgress entity = progress.findById(new ListeningProgress.Key(userId, episodeId))
                .map(existing -> {
                    existing.update(body.positionSeconds());
                    return existing;
                })
                .orElseGet(() -> {
                    // A new row only for an episode this listener can see. Any UUID used to be accepted, so a
                    // signed-in account could fill its own partition with rows about nothing, and an unknown
                    // id answered 204 where the episode does not exist (core#201). An existing row may still
                    // move after its episode is hidden; it is the listener's.
                    if (episodes.findVisibleById(episodeId).isEmpty()) {
                        throw new dev.mosaicast.core.web.NotFoundException("Episode not found: " + episodeId);
                    }
                    return new ListeningProgress(userId, episodeId, body.positionSeconds());
                });
        progress.save(entity);
        return ResponseEntity.noContent().build();
    }

    public record UpdatePosition(@PositiveOrZero int positionSeconds) {
    }

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication).orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
