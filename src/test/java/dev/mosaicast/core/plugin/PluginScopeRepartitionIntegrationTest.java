// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.Scope;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plugin documents surviving the UUID → slug change of feed and episode scope ids (§4.1, §7.6).
 *
 * <p>The failure this guards against is silent by construction: {@code FeedAccessImpl} resolves both an old
 * UUID and a new slug, so a plugin holding a UUID kept getting the correct episode list and looked healthy,
 * while its documents were read from an empty partition and written into a second one nothing else
 * addressed. Nothing throws, nothing logs; the plugin simply behaves as though its data had never been
 * written.
 */
// RANDOM_PORT (not NONE): the app's SecurityConfig wires HttpSecurity, which needs a servlet web context.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class PluginScopeRepartitionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PluginScopeRepartition repartition;

    @Autowired
    private PluginDataService data;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * Writes straight to the table under a scope id the service layer would now canonicalise away.
     *
     * <p>Through a {@link TransactionTemplate} rather than {@code @Transactional} on this method: a test class
     * is not a Spring proxy, so the annotation would be inert and every insert would fail for want of a
     * transaction. Going under the service layer is the point — these rows have to look exactly like ones
     * written before canonicalisation existed.
     */
    private void writeLegacyRow(String pluginId, String scopeType, String scopeId, String key) {
        transactions.executeWithoutResult(status -> entityManager.createNativeQuery(
                "insert into plugin_data (plugin_id, scope_type, scope_id, key, value) "
                        + "values (?1, ?2, ?3, ?4, '{\"legacy\":true}'::jsonb)")
                .setParameter(1, pluginId)
                .setParameter(2, scopeType)
                .setParameter(3, scopeId)
                .setParameter(4, key)
                .executeUpdate());
    }

    @Test
    void feedSeasonAndEpisodeDocumentsAllFollowTheirSlug() {
        Feed feed = feeds.save(Feed.rss("https://example.test/repartition.xml", "Repartition Cast"));
        feed.assignSlugIfAbsent("repartition-cast");
        feeds.saveAndFlush(feed);
        EpisodeRef ref = refs.saveAndFlush(
                EpisodeRef.published(feed.getId(), "guid-rp-1", 1, 1, "repartition-cast-s01e01"));

        String feedUuid = feed.getId().toString();
        writeLegacyRow("rp", "feed", feedUuid, "note");
        writeLegacyRow("rp", "season", feedUuid + ":1", "note");
        // The branch that never existed — FeedSlugBackfill moved feed and season scopes and nothing moved
        // episode ones, though episode scope ids became slugs in the same release.
        writeLegacyRow("rp", "episode", ref.getId().toString(), "note");

        repartition.run();

        assertThat(data.getRaw("rp", Scope.feed("repartition-cast"), "note")).isPresent();
        assertThat(data.getRaw("rp", Scope.season("repartition-cast:1"), "note")).isPresent();
        assertThat(data.getRaw("rp", Scope.episode("repartition-cast-s01e01"), "note")).isPresent();
    }

    @Test
    void runsEveryBootRatherThanOnlyWhenSomethingWasJustMinted() {
        // The stranding this fixes: repartition lived inside the feed backfill's per-feed loop, which returns
        // early once every feed already has a slug. A document written under a UUID on any later boot could
        // therefore never be rescued, however many times the backfill re-ran.
        Feed feed = feeds.save(Feed.rss("https://example.test/later.xml", "Later Cast"));
        feed.assignSlugIfAbsent("later-cast");
        feeds.saveAndFlush(feed);

        repartition.run();                                    // nothing to do
        writeLegacyRow("rp2", "feed", feed.getId().toString(), "written-later");
        repartition.run();                                    // must still find it

        assertThat(data.getRaw("rp2", Scope.feed("later-cast"), "written-later")).isPresent();
    }

    @Test
    void aLiveDocumentIsNeverOverwrittenByAStaleOne() {
        // Both spellings can hold the same key if a plugin wrote under each. The slug-keyed one is the live
        // one; moving the UUID-keyed row onto it would violate the primary key, and forcing it would destroy
        // current data to restore older data — the exact loss this class exists to prevent.
        Feed feed = feeds.save(Feed.rss("https://example.test/clash.xml", "Clash Cast"));
        feed.assignSlugIfAbsent("clash-cast");
        feeds.saveAndFlush(feed);

        data.put("rp3", Scope.feed("clash-cast"), "note", "current");
        writeLegacyRow("rp3", "feed", feed.getId().toString(), "note");

        repartition.run();

        assertThat(data.get("rp3", Scope.feed("clash-cast"), "note", String.class)).contains("current");
    }

    @Test
    void aUuidAndItsSlugAddressOneDocument() {
        // Canonicalisation at the boundary, so nothing diverges again after the sweep has run. A plugin that
        // persisted a UUID before the change keeps reading and writing the same partition as everyone else.
        Feed feed = feeds.save(Feed.rss("https://example.test/alias.xml", "Alias Cast"));
        feed.assignSlugIfAbsent("alias-cast");
        feeds.saveAndFlush(feed);
        UUID uuid = feed.getId();

        data.put("rp4", Scope.feed(uuid.toString()), "note", "written-by-uuid");

        assertThat(data.get("rp4", Scope.feed("alias-cast"), "note", String.class))
                .contains("written-by-uuid");
        assertThat(data.query("rp4", Scope.feed("alias-cast"), "")).hasSize(1);
        assertThat(data.query("rp4", Scope.feed(uuid.toString()), "")).hasSize(1);
    }

    @Test
    void anIdMatchingNoEntityIsLeftAsItIs() {
        // A normaliser that threw would break the in-process store for a plugin acting on data it holds;
        // refusing unknown scopes is the HTTP boundary's job, where the caller can be told.
        data.put("rp5", Scope.feed(UUID.randomUUID().toString()), "note", "orphan");
        repartition.run();
        assertThat(data.query("rp5", Scope.site(), "")).isEmpty();
    }
}
