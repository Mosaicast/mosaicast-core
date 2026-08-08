// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves plugin documents from the UUID scope ids they were written under to the slugs that replaced them
 * (ARCHITECTURE §4.1, §7.6).
 *
 * <p>Feed and episode scope ids became public slugs. {@code FeedAccessImpl} resolves both forms, so a plugin
 * holding an old UUID still gets the right episode list and nothing looks broken — while
 * {@code store.get(Scope.feed(uuid), key)} reads an empty partition and {@code store.put(...)} writes into a
 * second one. Indistinguishable from data loss, which is what the slug rollout was supposed to avoid.
 *
 * <p>Runs on every boot rather than only when something was just minted. The previous version repartitioned
 * inside the feed backfill's per-feed loop, which returned early once every feed had a slug — so a document
 * written under a UUID at any point after that first boot was stranded permanently, and re-running the
 * backfill could never rescue it. Three statements against indexed columns is cheap enough to pay each start
 * for the guarantee that nothing is left behind.
 *
 * <p>Ordered after both slug backfills and before the HTTP port opens: it needs the slugs to exist, and no
 * request may observe a half-moved store.
 */
@Component
public class PluginScopeRepartition {

    private static final Logger log = LoggerFactory.getLogger(PluginScopeRepartition.class);

    private final EntityManager entityManager;

    public PluginScopeRepartition(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Repoints every document whose scope id is still the entity's UUID.
     *
     * <p>Native SQL rather than JPQL because each statement is a join against the entity table — the slug is
     * looked up, never recomputed, so this cannot mint a different slug than the one already in use.
     *
     * <p>Every statement carries a {@code NOT EXISTS} guard. A row can already exist at the destination when
     * a plugin wrote under both forms — during the window between the slug landing and this running, say. The
     * primary key is {@code (plugin_id, scope_type, scope_id, key)}, so moving onto an occupied key would
     * abort the whole migration on a constraint violation. Colliding rows are left where they are and
     * reported: the slug-keyed document is the live one, and silently overwriting it with a stale UUID-keyed
     * copy would be the data loss this class exists to prevent.
     */
    @Transactional
    public void run() {
        int feeds = entityManager.createNativeQuery("""
                update plugin_data d set scope_id = f.slug
                from feed f
                where d.scope_type = 'feed'
                  and d.scope_id = f.id::text
                  and f.slug is not null
                  and not exists (
                      select 1 from plugin_data t
                      where t.plugin_id = d.plugin_id and t.scope_type = 'feed'
                        and t.scope_id = f.slug and t.key = d.key)
                """).executeUpdate();

        // A season scope id is the host-owned encoding "<feedRef>:<season>" (§7.5), so only the prefix moves.
        int seasons = entityManager.createNativeQuery("""
                update plugin_data d
                   set scope_id = f.slug || substring(d.scope_id from position(':' in d.scope_id))
                from feed f
                where d.scope_type = 'season'
                  and f.slug is not null
                  and d.scope_id like f.id::text || ':%'
                  and not exists (
                      select 1 from plugin_data t
                      where t.plugin_id = d.plugin_id and t.scope_type = 'season'
                        and t.scope_id = f.slug || substring(d.scope_id from position(':' in d.scope_id))
                        and t.key = d.key)
                """).executeUpdate();

        // The branch that never existed: FeedSlugBackfill moved feed and season scopes and nothing moved
        // episode ones, even though episode scope ids became slugs in the same release.
        int episodes = entityManager.createNativeQuery("""
                update plugin_data d set scope_id = e.slug
                from episode_ref e
                where d.scope_type = 'episode'
                  and d.scope_id = e.id::text
                  and e.slug is not null
                  and not exists (
                      select 1 from plugin_data t
                      where t.plugin_id = d.plugin_id and t.scope_type = 'episode'
                        and t.scope_id = e.slug and t.key = d.key)
                """).executeUpdate();

        int moved = feeds + seasons + episodes;
        if (moved > 0) {
            log.info("Repointed {} plugin document(s) from UUID scope ids to slugs ({} feed, {} season, "
                    + "{} episode)", moved, feeds, seasons, episodes);
        }
        reportStragglers();
    }

    /**
     * Counts documents still sitting on a UUID scope id after the move.
     *
     * <p>Anything left is either a collision (a live slug-keyed document already holds that key) or an id
     * matching no entity at all. Both are worth an operator's attention and neither is safe to resolve
     * automatically — so this reports rather than deletes.
     */
    private void reportStragglers() {
        Number stranded = (Number) entityManager.createNativeQuery("""
                select count(*) from plugin_data d
                where d.scope_type in ('feed', 'season', 'episode')
                  and d.scope_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
                """).getSingleResult();
        if (stranded.longValue() > 0) {
            log.warn("{} plugin document(s) still use a UUID scope id and were not moved — either the entity "
                    + "no longer exists, or a document already occupies the same key under its slug. They "
                    + "remain readable only by a plugin passing the old UUID.", stranded.longValue());
        }
    }
}
