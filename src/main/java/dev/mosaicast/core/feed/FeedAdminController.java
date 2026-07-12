// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.feed.FeedRequests.CreateFeed;
import dev.mosaicast.core.feed.FeedRequests.CreatePlannedEpisode;
import dev.mosaicast.core.feed.FeedRequests.PreviewFeed;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin/podcaster feed management and planned-episode creation (ARCHITECTURE §5, §4.3, §5.4). These
 * endpoints are open in M1; RBAC (PODCASTER/ADMIN) is enforced in M2 (§8.5).
 */
@RestController
@RequestMapping("/api/admin/feeds")
public class FeedAdminController {

    private final FeedService feeds;

    public FeedAdminController(FeedService feeds) {
        this.feeds = feeds;
    }

    @GetMapping
    public List<FeedView> list() {
        return feeds.list();
    }

    @GetMapping("/{id}")
    public FeedView get(@PathVariable UUID id) {
        return feeds.get(id);
    }

    /** Validate a URL and preview its episodes before saving (§E4). */
    @PostMapping("/preview")
    public FeedPreview preview(@Valid @RequestBody PreviewFeed request) {
        return feeds.preview(request.url());
    }

    /** Add an RSS feed; it is polled immediately so its episodes appear at once. */
    @PostMapping
    public ResponseEntity<FeedView> create(@Valid @RequestBody CreateFeed request) {
        FeedView created = feeds.createRss(request.url(), request.title());
        return ResponseEntity.created(URI.create("/api/admin/feeds/" + created.id())).body(created);
    }

    /** "Refresh now" — poll a feed on demand (§5.4). */
    @PostMapping("/{id}/refresh")
    public PollOutcome refresh(@PathVariable UUID id) {
        return feeds.refreshNow(id);
    }

    @PostMapping("/{id}/enabled")
    public FeedView setEnabled(@PathVariable UUID id, @RequestParam boolean value) {
        return feeds.setEnabled(id, value);
    }

    /** Set a feed's poll interval in seconds (§5.4, "configurable per feed"); the service clamps the range. */
    @PostMapping("/{id}/poll-interval")
    public FeedView setPollInterval(@PathVariable UUID id, @RequestParam long seconds) {
        return feeds.setPollInterval(id, seconds);
    }

    /** Create a planned episode on a target feed (§4.3). */
    @PostMapping("/{feedId}/planned-episodes")
    public ResponseEntity<Map<String, UUID>> createPlanned(
            @PathVariable UUID feedId, @Valid @RequestBody CreatePlannedEpisode request) {
        UUID id = feeds.createPlannedEpisode(
                feedId, request.season(), request.episodeNo(), request.title(), request.description());
        return ResponseEntity.created(URI.create("/api/episodes/" + id)).body(Map.of("id", id));
    }

    /** A feed's outstanding fuzzy PLANNED-binding suggestions to confirm or dismiss (§5.3). */
    @GetMapping("/{feedId}/suggestions")
    public List<SuggestionView> suggestions(@PathVariable UUID feedId) {
        return feeds.listSuggestions(feedId);
    }

    /** Confirm a suggestion: bind the planned episode to the feed item (§5.3). */
    @PostMapping("/suggestions/{id}/confirm")
    public ResponseEntity<Void> confirmSuggestion(@PathVariable UUID id) {
        feeds.confirmSuggestion(id);
        return ResponseEntity.noContent().build();
    }

    /** Dismiss a suggestion without applying it (§5.3). */
    @DeleteMapping("/suggestions/{id}")
    public ResponseEntity<Void> dismissSuggestion(@PathVariable UUID id) {
        feeds.dismissSuggestion(id);
        return ResponseEntity.noContent().build();
    }
}
