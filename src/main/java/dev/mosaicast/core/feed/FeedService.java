// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.Objects;
import dev.mosaicast.core.plugin.PluginDataRepository;
import dev.mosaicast.core.log.LogSafe;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed administration and the manual/planned surface (ARCHITECTURE §5, §4.3): add/refresh/enable RSS
 * feeds and create planned episodes. Access control is enforced at the filter chain — PODCASTER or ADMIN
 * on {@code /api/admin/feeds/**} (§8.5) — not here.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final FeedRepository feeds;
    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final BindingSuggestionRepository suggestions;
    private final FeedPipeline pipeline;
    private final FeedSourceRegistry registry;
    private final OutboundTargetPolicy targets;
    private final PluginDataRepository pluginData;

    public FeedService(FeedRepository feeds, EpisodeRefRepository refs, EpisodeDisplayRepository displays,
                       BindingSuggestionRepository suggestions, FeedPipeline pipeline,
                       FeedSourceRegistry registry, OutboundTargetPolicy targets,
                       PluginDataRepository pluginData) {
        this.feeds = feeds;
        this.refs = refs;
        this.displays = displays;
        this.suggestions = suggestions;
        this.pipeline = pipeline;
        this.registry = registry;
        this.targets = targets;
        this.pluginData = pluginData;
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
                // A feed with no slug has no address, so publishing it produces links to /feeds/null and a
                // 404 behind each one. SlugBootstrap mints slugs before the port opens, so this should never
                // fire — it is here because the shell's types declare `slug: string` and a null would make
                // TypeScript's assurance false at runtime, which is the worst kind of wrong.
                .filter(feed -> feed.getSlug() != null && !feed.getSlug().isBlank())
                .map(feed -> PublicFeedView.of(feed, refs.countByFeedId(feed.getId())))
                .sorted(java.util.Comparator.comparing(
                        PublicFeedView::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Public detail of one feed for the shell's feed panel (§6.1) — cover, title, author, description. */
    @Transactional(readOnly = true)
    public FeedDetailView detail(String slugOrId) {
        Feed feed = resolvePublic(slugOrId);
        return FeedDetailView.of(feed, refs.countByFeedId(feed.getId()));
    }

    /**
     * Resolves a public feed reference: the slug it is addressed by today, or its UUID.
     *
     * <p>The UUID is still accepted on purpose. Feed URLs were UUID-shaped until this release, so a link
     * someone shared or bookmarked before it would otherwise 404 — and the id is neither secret nor
     * ambiguous with a slug (slugs never parse as UUIDs). New links are minted with the slug everywhere.
     */
    @Transactional(readOnly = true)
    public Feed resolvePublic(String slugOrId) {
        return feeds.findBySlug(slugOrId)
                .or(() -> asUuid(slugOrId).flatMap(feeds::findById))
                .filter(Feed::isEnabled)
                .orElseThrow(() -> new NotFoundException("Feed not found: " + slugOrId));
    }

    /** The internal id behind a public feed reference, for the queries that still address by UUID. */
    @Transactional(readOnly = true)
    public UUID resolvePublicId(String slugOrId) {
        return resolvePublic(slugOrId).getId();
    }

    /**
     * Resolves a public feed reference without throwing — the form a <em>filter</em> needs.
     *
     * <p>{@link #resolvePublicId} throws {@link NotFoundException}, which is right when the feed is the
     * resource being addressed and wrong when it is one optional query parameter among several: an admin
     * disabling a feed should not turn {@code /api/episodes} and {@code /api/tags} into 404s for everyone
     * holding a filtered link.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findPublicId(String slugOrId) {
        return feeds.findBySlug(slugOrId)
                .or(() -> asUuid(slugOrId).flatMap(feeds::findById))
                .filter(Feed::isEnabled)
                .map(Feed::getId);
    }

    private static java.util.Optional<UUID> asUuid(String value) {
        try {
            return java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
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

    /**
     * Adds an RSS feed and polls it immediately so its episodes appear right away.
     *
     * <p>Not {@code @Transactional}: {@link FeedPipeline#poll} performs an outbound HTTP request, and a
     * transaction open around it would hold a pooled connection and the feed's row lock for the whole
     * round-trip. The save below is its own transaction (Spring Data gives every repository call one), which
     * is all this method needs.
     */
    public FeedView createRss(String url, String title) {
        validateHttpUrl(url);
        if (feeds.existsByUrl(url)) {
            // A 409, because the feed exists: this is a state conflict, not a malformed request. Adding it
            // twice produced two rows, two complete episode sets and every episode listed twice, since the
            // GUID uniqueness constraint is scoped to a feed.
            throw new ConflictException("This feed is already here.");
        }
        String resolvedTitle = (title == null || title.isBlank()) ? channelTitle(url) : title;
        Feed feed = Feed.rss(url, resolvedTitle);
        // Minted here rather than in a lifecycle hook so it exists before the first poll writes episodes:
        // episode slugs read the feed's title, and plugin scope ids read this one.
        feed.assignSlugIfAbsent(FeedSlug.generate(resolvedTitle, feeds::existsBySlug));
        feed = feeds.save(feed);
        log.info("Feed added: '{}' ({}) — polling now", LogSafe.of(resolvedTitle), LogSafe.of(url));
        pipeline.poll(feed);
        return FeedView.of(feed, refs.countByFeedId(feed.getId()));
    }

    /**
     * Removes a feed and everything that only existed because of it (§5.1).
     *
     * <p>A feed could be disabled but never deleted — there was no {@code DELETE} on this surface at all,
     * only in the UI. Disabling correctly hides a feed from every public surface, but the row, its episode
     * refs, the display snapshots and the fetched show notes stayed in the database with no supported way
     * to remove them. A feed added by typo, a feed whose URL was hijacked, and a feed pulling content that
     * must come down were all permanent (core#175). For a project with an account-erasure pipeline and an
     * admin retry queue, third-party content having no equivalent path was the gap.
     *
     * <p>What goes: the feed, its episode refs, and — by {@code ON DELETE CASCADE} from {@code episode_ref}
     * — their display snapshots, tags, listening progress and pins. Plus the plugin documents stored
     * against the scopes those slugs named, which cascade from nothing because a plugin's store is keyed
     * by the host's scope strings rather than by a foreign key. What stays: the tag vocabulary, which is
     * the site's and may still be carried by other feeds' episodes.
     *
     * @return what was removed, so the admin UI can say it rather than claim it
     */
    @Transactional
    public DeletedFeed delete(UUID id) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("No such feed: " + id));
        List<EpisodeRef> episodes = refs.findByFeedId(id);

        // The scope ids as plugins addressed them — the episode slug, the feed slug, and every season of
        // this feed. Collected before the rows go, because afterwards there is nothing left to derive them
        // from.
        List<String> episodeScopes = episodes.stream().map(EpisodeRef::getSlug).filter(Objects::nonNull)
                .toList();
        List<String> feedScopes = feed.getSlug() == null ? List.of(feed.getId().toString())
                : List.of(feed.getSlug(), feed.getId().toString());
        List<String> seasonScopes = episodes.stream()
                .map(EpisodeRef::getSeason)
                .filter(Objects::nonNull)
                .distinct()
                .map(season -> (feed.getSlug() == null ? feed.getId().toString() : feed.getSlug())
                        + ":" + season)
                .toList();

        int documents = 0;
        if (!episodeScopes.isEmpty()) {
            documents += pluginData.deleteByScope("episode", episodeScopes);
        }
        documents += pluginData.deleteByScope("feed", feedScopes);
        if (!seasonScopes.isEmpty()) {
            documents += pluginData.deleteByScope("season", seasonScopes);
        }

        refs.deleteAll(episodes);
        feeds.delete(feed);

        log.info("Deleted feed '{}' ({}): {} episode(s), {} plugin document(s)",
                LogSafe.of(feed.getTitle()), id, episodes.size(), documents);
        return new DeletedFeed(episodes.size(), documents);
    }

    /** What a feed deletion removed. */
    public record DeletedFeed(int episodes, int pluginDocuments) {
    }

    /**
     * "Refresh now" (§5.4): polls a feed on demand and returns what changed.
     *
     * <p>Not {@code @Transactional}, for the same reason as {@link #createRss}: this is the endpoint an
     * impatient podcaster clicks repeatedly, and it was the fastest way to exhaust the connection pool.
     */
    public PollOutcome refreshNow(UUID id) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        log.info("Manual refresh requested for feed '{}'", feed.getTitle());
        return pipeline.poll(feed);
    }

    @Transactional
    public FeedView setEnabled(UUID id, boolean enabled) {
        Feed feed = feeds.findById(id).orElseThrow(() -> new NotFoundException("Feed not found: " + id));
        feed.setEnabled(enabled);
        feeds.save(feed);
        // Disabling hides the feed and its episodes from the whole public site (§5.4/§6.1), so it is a
        // bigger deal than it looks in the admin toggle.
        log.info("Feed '{}' {} — {} on the public site", feed.getTitle(),
                enabled ? "enabled" : "disabled", enabled ? "visible again" : "now hidden");
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
        log.info("Feed '{}' poll interval set to {} s{}", feed.getTitle(), clamped,
                clamped == seconds ? "" : " (clamped from " + seconds + " s)");
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
        UUID plannedId = refs.save(planned).getId();
        log.info("Planned episode created on '{}': S{}E{} '{}' (slug {})", feed.getTitle(),
                season == null ? "?" : season, episodeNo == null ? "?" : episodeNo, title, slug);
        return plannedId;
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
            // The operator gets the detail in the log; the caller gets one message for every failure.
            //
            // This is the preview endpoint's response body, and it used to carry the underlying reason
            // through verbatim. "Failed to parse feed body", "Feed fetch I/O error" and "failed with HTTP 403"
            // are three different answers to "is something listening on this internal address", which turns a
            // feed form into a network scanner for anyone holding a podcaster account.
            log.warn("Feed fetch rejected for '{}': {}", url, e.getMessage());
            throw new IllegalArgumentException(OutboundTargetPolicy.BLOCKED_MESSAGE);
        }
    }

    private void validateHttpUrl(String url) {
        targets.validate(url);
    }

    /**
     * The feed's own channel title, for an add that left the title blank.
     *
     * <p>{@code CreateFeed} documents a blank title as "defaults to the feed's channel title", and the
     * fetched {@code FetchResult.feedTitle()} was never used by any writing path — the poll only carries
     * image/author/description into {@code updateChannelMeta}, so the feed kept the host name
     * ({@link #deriveTitle}) forever. That name is not only what the admin list and the feed panel show: the
     * feed slug and every episode slug are minted from it, and both are immutable once set, so getting it
     * right has to happen here rather than on a later poll.
     *
     * <p>Costs one fetch, only on the blank-title path, and falls back to the host name when the feed cannot
     * be read — an unreachable feed is still added, exactly as before.
     */
    private String channelTitle(String url) {
        try {
            String channel = fetchOrThrow(url).feedTitle();
            return channel == null || channel.isBlank() ? deriveTitle(url) : channel.trim();
        } catch (RuntimeException unreadable) {
            log.warn("Could not read a channel title from '{}'; naming the feed after its host", url);
            return deriveTitle(url);
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
