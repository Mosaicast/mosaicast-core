// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeRefRepository;
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

    public FeedAccessImpl(EpisodeQueryService query, EpisodeRefRepository refs) {
        this.query = query;
        this.refs = refs;
    }

    /** The visible episode summaries in a scope, in canonical order — the source for ids and labels. */
    public List<EpisodeSummary> summariesIn(Scope scope) {
        return switch (scope.type()) {
            case SITE -> query.listSite(null, null, null, true, Pageable.unpaged()).getContent();
            case FEED -> parseUuid(scope.id())
                    .map(feedId -> query.listByFeed(feedId, null, Pageable.unpaged()).getContent())
                    .orElseGet(List::of);
            case SEASON -> parseSeason(scope.id())
                    .map(fs -> query.listByFeed(fs.feedId(), fs.season(), Pageable.unpaged()).getContent())
                    .orElseGet(List::of);
            case EPISODE -> refs.findVisibleBySlug(scope.id())
                    .map(ref -> List.of(EpisodeSummary.from(ref, query.displayForSlug(scope.id()))))
                    .orElseGet(List::of);
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

    private static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Splits a season scope id {@code "<feedId>:<season>"} (feed UUIDs contain no colon). */
    private static Optional<FeedSeason> parseSeason(String id) {
        int sep = id.lastIndexOf(':');
        if (sep <= 0 || sep == id.length() - 1) {
            return Optional.empty();
        }
        Optional<UUID> feedId = parseUuid(id.substring(0, sep));
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
