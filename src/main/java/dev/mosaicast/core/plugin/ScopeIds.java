// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.Scope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reduces a scope id to the one form the doc store partitions on: the entity's public slug (§4.1, §7.6).
 *
 * <p>Feed and episode scope ids used to be UUIDs and are now slugs. {@code FeedAccessImpl} accepts either,
 * deliberately, so a plugin that persisted a UUID before the change keeps resolving episodes correctly — and
 * that tolerance is exactly what made the mismatch invisible: the plugin's episode list looked right while
 * its documents quietly moved to a partition nothing else addressed. Two spellings of one entity have to be
 * one partition, or they are two.
 *
 * <p>Canonicalising toward the slug rather than the UUID because the slug is what the URL, the API and every
 * other surface already use; the UUID is the legacy form and should end up with no readers.
 *
 * <p>An id matching no entity is returned unchanged. Rejecting it belongs at the HTTP boundary, where the
 * caller can be told (see {@code PluginDataController}); this is a normaliser, and a normaliser that throws
 * would break the in-process store for a plugin acting on data it already holds.
 */
@Component
public class ScopeIds {

    private final FeedRepository feeds;
    private final EpisodeRefRepository refs;

    public ScopeIds(FeedRepository feeds, EpisodeRefRepository refs) {
        this.feeds = feeds;
        this.refs = refs;
    }

    /** The scope's id in its canonical (slug) form. */
    @Transactional(readOnly = true)
    public String canonical(Scope scope) {
        String id = scope.id();
        return switch (scope.type()) {
            case SITE -> id;
            case FEED -> feedSlug(id).orElse(id);
            case SEASON -> canonicalSeason(id);
            case EPISODE -> episodeSlug(id).orElse(id);
        };
    }

    /** A season id is the host-owned {@code "<feedRef>:<season>"} encoding, so only the feed part moves. */
    private String canonicalSeason(String id) {
        int sep = id.lastIndexOf(':');
        if (sep <= 0) {
            return id;
        }
        String feedRef = id.substring(0, sep);
        return feedSlug(feedRef).map(slug -> slug + id.substring(sep)).orElse(id);
    }

    /** The slug for a feed reference, but only when the reference was a UUID that resolves. */
    private Optional<String> feedSlug(String ref) {
        return asUuid(ref)
                .flatMap(feeds::findById)
                .map(Feed::getSlug)
                .filter(slug -> slug != null && !slug.isBlank());
    }

    private Optional<String> episodeSlug(String ref) {
        return asUuid(ref)
                .flatMap(refs::findById)
                .map(EpisodeRef::getSlug)
                .filter(slug -> slug != null && !slug.isBlank());
    }

    /**
     * Only a well-formed UUID is worth a lookup.
     *
     * <p>A slug can never parse as one, so the common path — a plugin passing the slug it was given — costs
     * a string parse and no query at all.
     */
    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
