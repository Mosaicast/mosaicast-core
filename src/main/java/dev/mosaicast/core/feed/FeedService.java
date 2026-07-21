// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
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
    private final EpisodeDisplayRepository displays;
    private final BindingSuggestionRepository suggestions;
    private final FeedPipeline pipeline;
    private final FeedSourceRegistry registry;

    public FeedService(FeedRepository feeds, EpisodeRefRepository refs, EpisodeDisplayRepository displays,
                       BindingSuggestionRepository suggestions, FeedPipeline pipeline,
                       FeedSourceRegistry registry) {
        this.feeds = feeds;
        this.refs = refs;
        this.displays = displays;
        this.suggestions = suggestions;
        this.pipeline = pipeline;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<FeedView> list() {
        return feeds.findAll().stream()
                .map(feed -> FeedView.of(feed, refs.countByFeedId(feed.getId())))
                .toList();
    }

    /**
     * The public feed catalog (§6.1): every feed as a slim, anonymous-safe {@link PublicFeedView}, ordered
     * by title. Unlike {@link #list()} this omits source URL / poll state so nothing admin-only leaks to
     * unauthenticated callers.
     */
    @Transactional(readOnly = true)
    public List<PublicFeedView> catalog() {
        // Disabled feeds are hidden from the public site (tabs/browse) — enabled gates public visibility, not
        // just polling. Their episodes are likewise excluded from the public episode reads.
        return feeds.findByEnabledTrue().stream()
                .map(feed -> PublicFeedView.of(feed, refs.countByFeedId(feed.getId())))
                .sorted(java.util.Comparator.comparing(
                        PublicFeedView::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Public detail of one feed for the shell's feed panel (§6.1) — cover, title, author, description. */
    @Transactional(readOnly = true)
    public FeedDetailView detail(UUID id) {
        Feed feed = feeds.findById(id)
                .filter(Feed::isEnabled)
                .orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        return FeedDetailView.of(feed, refs.countByFeedId(id));
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

    /** The lowest / highest poll interval an operator may set (§5.4) — polite to feed hosts, still useful. */
    static final Duration MIN_POLL_INTERVAL = Duration.ofMinutes(5);
    static final Duration MAX_POLL_INTERVAL = Duration.ofDays(7);

    /**
     * Sets a feed's poll interval (ARCHITECTURE §5.4 — "configurable per feed"), clamped to a polite
     * range so a feed host can't be hammered. Conditional GET already makes an unchanged poll a cheap 304
     * and the scheduler's backoff still applies on top.
     */
    @Transactional
    public FeedView setPollInterval(UUID id, long seconds) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        long clamped = Math.max(MIN_POLL_INTERVAL.toSeconds(), Math.min(MAX_POLL_INTERVAL.toSeconds(), seconds));
        feed.setPollInterval(Duration.ofSeconds(clamped));
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
                title, description == null ? "" : description, null, null, null, null, null, null, null);
        String slug = dev.mosaicast.core.episode.EpisodeSlug.generate(
                feed.getTitle(), season, episodeNo, title, refs::existsBySlug);
        EpisodeRef planned = EpisodeRef.planned(feed.getId(), season, episodeNo, provisional, slug);
        return refs.save(planned).getId();
    }

    /** The feed's outstanding fuzzy-binding suggestions, strongest match first (§5.3). */
    @Transactional(readOnly = true)
    public List<SuggestionView> listSuggestions(UUID feedId) {
        return suggestions.findByFeedIdOrderBySimilarityDesc(feedId).stream()
                .map(SuggestionView::of)
                .toList();
    }

    /**
     * Confirms a fuzzy-binding suggestion (§5.3): binds the PLANNED episode to the feed item the reconciler
     * auto-created a PUBLISHED ref for. The feed item's GUID + season/episode and its display snapshot move
     * onto the planned ref (so plugin data that hung on the planned id is now the live episode), and the
     * auto-created ref is deleted. Safe pre-M4: the auto-created ref carries no plugin data yet.
     *
     * <p>The GUID is unique per feed, so the auto-created ref must be deleted (and that delete flushed)
     * <em>before</em> the planned ref takes the GUID, or the two collide on {@code uq_episode_ref_feed_guid}.
     */
    @Transactional
    public void confirmSuggestion(UUID suggestionId) {
        BindingSuggestion suggestion = suggestions.findById(suggestionId)
                .orElseThrow(() -> new NotFoundException("Suggestion not found: " + suggestionId));

        EpisodeRef planned = refs.findById(suggestion.getPlannedRefId())
                .orElseThrow(() -> new NotFoundException("Planned episode no longer exists"));
        if (planned.getStatus() != EpisodeStatus.PLANNED) {
            // Already bound (auto or via another confirm) — the suggestion is stale, drop it.
            suggestions.delete(suggestion);
            throw new ConflictException("This planned episode is no longer awaiting a binding");
        }

        EpisodeRef auto = refs.findByFeedIdAndExternalGuid(suggestion.getFeedId(), suggestion.getRawGuid())
                .orElseThrow(() -> new NotFoundException("The feed item is no longer present"));

        // Carry the feed item's presentation snapshot and relations over to the planned ref.
        DisplaySnapshot snapshot = displays.findById(auto.getId())
                .map(EpisodeDisplay::getSnapshot)
                .orElse(null);
        Integer season = auto.getSeason();
        Integer episodeNo = auto.getEpisodeNo();

        // Free the GUID first: delete the auto ref's display + the ref, and flush so the DELETE hits the DB
        // before the planned UPDATE claims the same (feed, guid). CASCADE would drop the display anyway, but
        // deleting it explicitly keeps JPA's view consistent.
        displays.deleteById(auto.getId());
        refs.delete(auto);
        refs.flush();

        planned.bindToFeedItem(suggestion.getRawGuid(), season, episodeNo);
        refs.save(planned);
        if (snapshot != null) {
            displays.save(new EpisodeDisplay(planned.getId(), snapshot));
        }

        // This planned ref is bound and this feed item is claimed — clear every now-moot suggestion for either
        // (covers this suggestion plus rival proposals across the two).
        suggestions.deleteByPlannedRefId(planned.getId());
        suggestions.deleteByFeedIdAndRawGuid(suggestion.getFeedId(), suggestion.getRawGuid());
    }

    /** Dismisses a fuzzy-binding suggestion without applying it (§5.3). */
    @Transactional
    public void dismissSuggestion(UUID suggestionId) {
        if (!suggestions.existsById(suggestionId)) {
            throw new NotFoundException("Suggestion not found: " + suggestionId);
        }
        suggestions.deleteById(suggestionId);
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
