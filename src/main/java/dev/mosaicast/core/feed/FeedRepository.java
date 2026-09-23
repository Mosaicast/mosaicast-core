// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for configured feed sources ({@link Feed}). */
public interface FeedRepository extends JpaRepository<Feed, UUID> {

    /** Enabled feeds the scheduler should poll. */
    List<Feed> findByEnabledTrue();

    /**
     * Loads a feed with a pessimistic write lock on its row, so concurrent polls of the same feed (a
     * scheduler tick and a "refresh now") serialize instead of both inserting the same GUID and hitting
     * {@code uq_episode_ref_feed_guid} (ARCHITECTURE §5.4). Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Feed f where f.id = :id")
    Optional<Feed> lockById(@Param("id") UUID id);

    /** Resolves a feed by its public slug — the identifier in URLs, the feed API and the plugin contract. */
    Optional<Feed> findBySlug(String slug);

    /**
     * Whether a feed with this exact URL is already here (§5.1).
     *
     * <p>Exact, not normalised, and that is the honest limit: {@code http://x/feed} and
     * {@code https://x/feed/} are the same feed and this will not say so. What it catches is the case that
     * actually happens — the same address pasted twice — where every episode appeared twice on the site,
     * because the GUID unique constraint is feed-scoped and {@code findSiteVisibleIds} has no cross-feed
     * dedup (core#184).
     */
    boolean existsByUrl(String url);

    /** Uniqueness check for slug minting. */
    boolean existsBySlug(String slug);

    /** Feeds created before slugs existed, for the one-time backfill. */
    List<Feed> findBySlugIsNull();
}
