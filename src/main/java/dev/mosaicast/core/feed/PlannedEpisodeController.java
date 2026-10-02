// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.feed.PlannedEpisodeService.MatchCandidate;
import dev.mosaicast.core.feed.PlannedEpisodeService.PlannedView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Planning episodes (ARCHITECTURE §4.3, core#252), for podcasters and admins — by session, or by access token
 * from a script:
 *
 * <pre>
 * POST   /api/admin/feeds/{feed}/planned-episodes        plan one; {feed} is the feed's slug or id
 * GET    /api/admin/episodes/planned[?feed=]             every planned episode, quiet and announced
 * PATCH  /api/admin/episodes/{slug}                      edit (title, description, numbers, announceAt)
 * POST   /api/admin/episodes/{slug}/announce             public now
 * DELETE /api/admin/episodes/{slug}                      cancel, with the plugin data prepared for it
 * GET    /api/admin/episodes/{slug}/match-candidates     released episodes it could be matched to
 * POST   /api/admin/episodes/{slug}/match                take over an imported duplicate's feed item
 * </pre>
 *
 * <p>Creation answers with the slug, so a follow-up call can prepare plugin content for the episode at once —
 * a bingo on {@code episode/<slug>} — while it is still quiet.
 */
@RestController
public class PlannedEpisodeController {

    private final PlannedEpisodeService planned;

    public PlannedEpisodeController(PlannedEpisodeService planned) {
        this.planned = planned;
    }

    /**
     * A plan to create.
     *
     * @param announceAt {@code null}/absent for quiet until announced, {@code "now"}, or an ISO-8601 instant
     * @param clientRef  the caller's own reference: the same one again returns the existing plan (200) rather
     *                   than creating a second
     */
    public record CreatePlanned(@Min(0) Integer season, @Min(0) Integer episodeNo,
                                @NotBlank @Size(max = 200) String title, @Size(max = 200_000) String description,
                                String announceAt,
                                @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String clientRef) {
    }

    /** Which imported episode to match a planned one to. */
    public record MatchRequest(@NotBlank String episode) {
    }

    @PostMapping("/api/admin/feeds/{feedRef}/planned-episodes")
    public ResponseEntity<PlannedView> create(@PathVariable String feedRef,
                                              @Valid @RequestBody CreatePlanned request) {
        PlannedEpisodeService.Created result = planned.create(feedRef, request.season(), request.episodeNo(),
                request.title(), request.description(), request.announceAt(), request.clientRef());
        if (!result.created()) {
            return ResponseEntity.ok(result.plan());
        }
        return ResponseEntity.created(URI.create(result.plan().url())).body(result.plan());
    }

    @GetMapping("/api/admin/episodes/planned")
    public List<PlannedView> list(@RequestParam(required = false) String feed) {
        return planned.list(feed);
    }

    @PatchMapping("/api/admin/episodes/{slug}")
    public PlannedView update(@PathVariable String slug, @RequestBody JsonNode patch) {
        return planned.update(slug, patch);
    }

    @PostMapping("/api/admin/episodes/{slug}/announce")
    public PlannedView announce(@PathVariable String slug) {
        return planned.announce(slug);
    }

    /** How many plugin documents went with the cancelled episode. */
    public record Cancelled(int pluginDocuments) {
    }

    @DeleteMapping("/api/admin/episodes/{slug}")
    public Cancelled cancel(@PathVariable String slug) {
        return new Cancelled(planned.cancel(slug));
    }

    @GetMapping("/api/admin/episodes/{slug}/match-candidates")
    public List<MatchCandidate> candidates(@PathVariable String slug) {
        return planned.candidates(slug);
    }

    @PostMapping("/api/admin/episodes/{slug}/match")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public PlannedView match(@PathVariable String slug, @Valid @RequestBody MatchRequest request) {
        return planned.match(slug, request.episode());
    }
}
