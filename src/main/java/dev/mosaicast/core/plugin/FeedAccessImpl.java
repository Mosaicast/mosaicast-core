// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Host-side {@link FeedAccess} (ARCHITECTURE §6.1/§7.4): the host — not the plugin — resolves which
 * episodes belong to a scope, reusing the same enabled-feed + non-WITHDRAWN visibility filter the shell
 * uses. Shared by all plugins (episodes are not plugin-scoped); the doc store is what is per-plugin.
 *
 * <p>Season scopes carry a host-defined opaque id {@code "<feedId>:<season>"} — the {@link Scope} API gives
 * a season a single string id, but a season needs both a feed and a number.
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

    @Override
    public List<String> episodesIn(Scope scope) {
        return switch (scope.type()) {
            case SITE -> refs.findSiteVisibleIds(null, null, null, true, Pageable.unpaged())
                    .getContent().stream().map(UUID::toString).toList();
            case FEED -> parseUuid(scope.id())
                    .map(feedId -> ids(refs.findVisibleIds(feedId, null)))
                    .orElseGet(List::of);
            case SEASON -> parseSeason(scope.id())
                    .map(fs -> ids(refs.findVisibleIds(fs.feedId(), fs.season())))
                    .orElseGet(List::of);
            case EPISODE -> parseUuid(scope.id())
                    .flatMap(refs::findVisibleById)
                    .map(ref -> List.of(ref.getId().toString()))
                    .orElseGet(List::of);
        };
    }

    @Override
    public DisplaySnapshot display(String refId) {
        return parseUuid(refId).map(query::displayFor).orElse(EMPTY);
    }

    private static List<String> ids(List<UUID> uuids) {
        return uuids.stream().map(UUID::toString).toList();
    }

    private static java.util.Optional<UUID> parseUuid(String s) {
        try {
            return java.util.Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** Splits a season scope id {@code "<feedId>:<season>"} (feed UUIDs contain no colon). */
    private static java.util.Optional<FeedSeason> parseSeason(String id) {
        int sep = id.lastIndexOf(':');
        if (sep <= 0 || sep == id.length() - 1) {
            return java.util.Optional.empty();
        }
        java.util.Optional<UUID> feedId = parseUuid(id.substring(0, sep));
        if (feedId.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new FeedSeason(feedId.get(), Integer.parseInt(id.substring(sep + 1))));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private record FeedSeason(UUID feedId, int season) {
    }
}
