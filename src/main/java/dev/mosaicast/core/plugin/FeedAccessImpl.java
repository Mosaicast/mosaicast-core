// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Host-side {@link FeedAccess} (ARCHITECTURE §6.1/§7.4): the host — not the plugin — resolves which
 * episodes belong to a scope, reusing the same enabled-feed + non-WITHDRAWN visibility filter (and canonical
 * order) the shell uses. Episode ids handed to plugins are the **public slug** (§4.1), so the id a plugin puts
 * in a doc-store path (`data/episode/{slug}/…`) matches the episode's public URL.
 *
 * <p>Season scopes carry a host-defined opaque id {@code "<feedId>:<season>"} — the {@link Scope} API gives a
 * season a single string id, but a season needs both a feed and a number.
 */
@Component
public class FeedAccessImpl implements FeedAccess {

    /** Empty presentation returned for an unresolvable ref (never null, per the contract). */
    private static final DisplaySnapshot EMPTY =
            new DisplaySnapshot("", "", null, null, null, null, null, null, null);

    private final EpisodeQueryService query;
    private final EpisodeRefRepository refs;
    private final FeedRepository feeds;

    public FeedAccessImpl(EpisodeQueryService query, EpisodeRefRepository refs, FeedRepository feeds) {
        this.query = query;
        this.refs = refs;
        this.feeds = feeds;
    }

    /**
     * The visible episode summaries in a scope, in canonical order — the source for ids and labels.
     *
     * <p>Unpaged, because {@link #episodesIn} is the SDK contract and a plugin resolving a scope needs all of
     * it. That is affordable in-process, where the caller is a plugin the operator installed. It is
     * <em>not</em> affordable over an anonymous HTTP endpoint, so {@link ScopeEpisodesController} uses
     * {@link #summariesIn(Scope, Pageable)} instead.
     */
    public List<EpisodeSummary> summariesIn(Scope scope) {
        return summariesIn(scope, Pageable.unpaged());
    }

    /** As {@link #summariesIn(Scope)}, bounded — the form anything reachable from the network should use. */
    public List<EpisodeSummary> summariesIn(Scope scope, Pageable pageable) {
        return switch (scope.type()) {
            case SITE -> query.listSite(null, null, null, true, pageable).getContent();
            case FEED -> resolveFeed(scope.id())
                    .map(feedId -> query.listByFeed(feedId, null, pageable).getContent())
                    .orElseGet(List::of);
            case SEASON -> parseSeason(scope.id())
                    .map(fs -> query.listByFeed(fs.feedId(), fs.season(), pageable).getContent())
                    .orElseGet(List::of);
            case EPISODE -> refs.findVisibleBySlug(scope.id())
                    .map(ref -> List.of(EpisodeSummary.from(ref, query.displayForSlug(scope.id()))))
                    .orElseGet(List::of);
            // A user partition holds no episodes — it is a storage scope, not a level of the site (§7.5).
            case USER -> List.of();
        };
    }

    @Override
    public List<String> episodesIn(Scope scope) {
        return summariesIn(scope).stream().map(EpisodeSummary::slug).toList();
    }

    @Override
    public DisplaySnapshot display(String refId) {
        // refId is the public slug.
        DisplaySnapshot snapshot = query.displayForSlug(refId);
        return snapshot == null ? EMPTY : snapshot;
    }

    /**
     * Whether a scope addresses an entity that actually exists.
     *
     * <p>The doc store partitions on {@code (pluginId, scopeType, scopeId)} and took {@code scopeId} straight
     * from the request path, so any string at all opened a fresh partition. That let a caller write into
     * partitions no episode, feed or season will ever correspond to — invisible to every admin surface,
     * uncounted by "purge plugin data" until the plugin itself is purged, and unbounded in number. Addressing
     * something is now at least a claim that it exists.
     *
     * <p>The SITE scope is a singleton and always resolves; {@code Scope}'s canonical constructor normalizes
     * its id, so there is nothing to check.
     */
    public boolean exists(Scope scope) {
        return switch (scope.type()) {
            // Both singletons the host owns rather than the client naming: SITE is the one site, and USER
            // resolves to whoever is calling — an id that got this far was already substituted server-side.
            case SITE, USER -> true;
            case FEED -> resolveFeed(scope.id()).isPresent();
            case SEASON -> parseSeason(scope.id()).isPresent();
            case EPISODE -> refs.findVisibleBySlug(scope.id()).isPresent();
        };
    }

    /**
     * Resolves a feed scope id. It is the feed's **public slug** — the same value that appears in the URL
     * and partitions the plugin's doc store, matching how the episode scope has worked since slugs landed.
     * A UUID still resolves, because a plugin that stored one before this release must keep working.
     */
    private Optional<UUID> resolveFeed(String id) {
        return feeds.findBySlug(id).map(Feed::getId).or(() -> parseUuid(id));
    }

    private static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Splits a season scope id {@code "<feed>:<season>"}, where {@code <feed>} is the feed's public slug
     * (neither a slug nor a UUID contains a colon, so the last one is unambiguous).
     */
    private Optional<FeedSeason> parseSeason(String id) {
        int sep = id.lastIndexOf(':');
        if (sep <= 0 || sep == id.length() - 1) {
            return Optional.empty();
        }
        Optional<UUID> feedId = resolveFeed(id.substring(0, sep));
        if (feedId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FeedSeason(feedId.get(), Integer.parseInt(id.substring(sep + 1))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private record FeedSeason(UUID feedId, int season) {
    }
}
