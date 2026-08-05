// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the public {@link Feed#getSlug() slug} for feeds created before feed slugs existed, and moves
 * any plugin data that was partitioned under the old scope ids with it.
 *
 * <p>Runs once at startup, touching only rows whose slug is null, so it is idempotent. Ordered before the
 * plugin loader ({@code @Order(0)}) so a plugin's {@code register(ctx)} already sees the final scope ids —
 * a plugin that read {@code data/feed/<uuid>/…} during boot and wrote back afterwards would otherwise
 * re-create the partition this just migrated away from.
 *
 * <p><strong>Why the data has to move.</strong> A plugin addresses its documents by
 * {@code scope.id}, and for a feed that id is now the slug. Leaving the rows keyed by UUID would not fail
 * loudly — it would simply present every feed-scoped and season-scoped document as missing, which looks
 * like data loss to the operator and is indistinguishable from it to the plugin. The rewrite is a plain
 * {@code UPDATE} of the key columns, in the same transaction as the minting, so either both happened or
 * neither did.
 */
@Component
@Order(-1)
public class FeedSlugBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeedSlugBackfill.class);

    private final FeedRepository feeds;
    private final EntityManager entityManager;

    public FeedSlugBackfill(FeedRepository feeds, EntityManager entityManager) {
        this.feeds = feeds;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Feed> missing = feeds.findBySlugIsNull();
        if (missing.isEmpty()) {
            return;
        }
        int movedDocs = 0;
        for (Feed feed : missing) {
            String slug = FeedSlug.generate(feed.getTitle(), feeds::existsBySlug);
            feed.assignSlugIfAbsent(slug);
            feeds.save(feed);
            // Flush so the next feed's uniqueness check sees this slug — two feeds called "News" must not
            // both mint `news`.
            entityManager.flush();
            movedDocs += repartition(feed.getId().toString(), slug);
        }
        log.info("Backfilled slugs for {} feed(s); moved {} plugin document(s) to the new scope ids",
                missing.size(), movedDocs);
    }

    /**
     * Repoints this feed's plugin documents at the slug: {@code feed} scope ids are the id itself,
     * {@code season} scope ids are {@code "<feedId>:<season>"} (the host-owned encoding, §7.5).
     */
    private int repartition(String feedId, String slug) {
        int feedScoped = entityManager
                .createQuery("update PluginData d set d.id.scopeId = :slug "
                        + "where d.id.scopeType = 'feed' and d.id.scopeId = :feedId")
                .setParameter("slug", slug)
                .setParameter("feedId", feedId)
                .executeUpdate();
        int seasonScoped = entityManager
                .createQuery("update PluginData d set d.id.scopeId = concat(:slug, substring(d.id.scopeId, "
                        + ":prefixLength)) "
                        + "where d.id.scopeType = 'season' and d.id.scopeId like :prefix")
                .setParameter("slug", slug)
                .setParameter("prefix", feedId + ":%")
                .setParameter("prefixLength", feedId.length() + 1)
                .executeUpdate();
        return feedScoped + seasonScoped;
    }
}
