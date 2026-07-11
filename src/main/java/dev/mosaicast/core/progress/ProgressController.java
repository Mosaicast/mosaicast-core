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

    public ProgressController(ListeningProgressRepository progress) {
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
                .orElseGet(() -> new ListeningProgress(userId, episodeId, body.positionSeconds()));
        progress.save(entity);
        return ResponseEntity.noContent().build();
    }

    public record UpdatePosition(@PositiveOrZero int positionSeconds) {
    }

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication).orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
