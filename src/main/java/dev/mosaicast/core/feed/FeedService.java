// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed administration and the manual/planned surface (ARCHITECTURE §5, §4.3): add/refresh/enable RSS
 * feeds and create planned episodes. Access control (PODCASTER/ADMIN) is layered on in M2; the endpoints
 * exist now so the pipeline is exercisable end-to-end.
 */
@Service
public class FeedService {

    private final FeedRepository feeds;
    private final EpisodeRefRepository refs;
    private final FeedPipeline pipeline;
    private final FeedSourceRegistry registry;

    public FeedService(FeedRepository feeds, EpisodeRefRepository refs,
                       FeedPipeline pipeline, FeedSourceRegistry registry) {
        this.feeds = feeds;
        this.refs = refs;
        this.pipeline = pipeline;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<FeedView> list() {
        return feeds.findAll().stream()
                .map(feed -> FeedView.of(feed, refs.countByFeedId(feed.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public FeedView get(UUID id) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        return FeedView.of(feed, refs.countByFeedId(feed.getId()));
    }

    /** Fetches a feed URL without persisting anything, for the add-feed form's preview (§E4). */
    public FeedPreview preview(String url) {
        FetchResult result = fetchOrThrow(url);
        List<String> sample = result.episodes().stream()
                .map(RawEpisode::title)
                .limit(10)
                .toList();
        String title = result.feedTitle() != null ? result.feedTitle() : url;
        return new FeedPreview(title, result.episodes().size(), sample);
    }

    /** Adds an RSS feed and polls it immediately so its episodes appear right away. */
    @Transactional
    public FeedView createRss(String url, String title) {
        validateHttpUrl(url);
        String resolvedTitle = (title == null || title.isBlank()) ? deriveTitle(url) : title;
        Feed feed = feeds.save(Feed.rss(url, resolvedTitle));
        pipeline.poll(feed);
        return FeedView.of(feed, refs.countByFeedId(feed.getId()));
    }

    /** "Refresh now" (§5.4): polls a feed on demand and returns what changed. */
    @Transactional
    public PollOutcome refreshNow(UUID id) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        return pipeline.poll(feed);
    }

    @Transactional
    public FeedView setEnabled(UUID id, boolean enabled) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        feed.setEnabled(enabled);
        feeds.save(feed);
        return FeedView.of(feed, refs.countByFeedId(feed.getId()));
    }

    /**
     * Creates a planned episode on a target feed (§4.3): identity now, so bingos attach before the RSS
     * item exists. When the real item appears with the same season/episode, reconciliation binds it and
     * flips PLANNED→PUBLISHED — the plugin data is untouched because it hung on the id, not the feed.
     */
    @Transactional
    public UUID createPlannedEpisode(UUID feedId, Integer season, Integer episodeNo, String title, String description) {
        Feed feed = feeds.findById(feedId)
                .orElseThrow(() -> new NotFoundException("Feed not found: " + feedId));
        DisplaySnapshot provisional = new DisplaySnapshot(
                title, description == null ? "" : description, null, null, null);
        EpisodeRef planned = EpisodeRef.planned(feed.getId(), season, episodeNo, provisional);
        return refs.save(planned).getId();
    }

    private FetchResult fetchOrThrow(String url) {
        validateHttpUrl(url);
        FeedSource source = registry.forType(Feed.TYPE_RSS)
                .orElseThrow(() -> new IllegalStateException("No RSS feed source registered"));
        try {
            return source.fetch(SourceConfig.initial(url));
        } catch (FetchException e) {
            throw new IllegalArgumentException("Could not read feed at " + url + ": " + e.getMessage());
        }
    }

    private static void validateHttpUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("Feed URL must be an http(s) URL");
        }
    }

    private static String deriveTitle(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }
}
