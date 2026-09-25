// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.episode.EpisodeTag;
import dev.mosaicast.core.episode.EpisodeTagRepository;
import dev.mosaicast.core.episode.RelatedProvider;
import dev.mosaicast.core.tag.TagSource;
import dev.mosaicast.plugin.api.Access;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Risk-point unit tests for the reconciler (ARCHITECTURE §5.2/§5.3, §13.5): the three GUID cases plus
 * PLANNED binding. Repositories are mocked; the reconciler mutates the entities it is handed, so the
 * assertions inspect those instances directly.
 */
@ExtendWith(MockitoExtension.class)
class ReconcilerTest {

    private static final UUID FEED = UUID.randomUUID();

    @Mock
    private EpisodeRefRepository refs;

    @Mock
    private EpisodeDisplayRepository displays;

    @Mock
    private EpisodeTagRepository tags;

    /** The shared vocabulary the feed's raw values are canonicalised through (§6.1). */
    @Mock
    private dev.mosaicast.core.tag.TagService vocabulary;

    /** Reconciling changes the episode set, which is what invalidates the related cache (§6.3). */
    @Mock
    private RelatedProvider related;

    private Reconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Reconciler(refs, displays, tags, vocabulary, related);
        // The real service canonicalises and extends the vocabulary; here only the canonical keys matter.
        lenient().when(vocabulary.ensureAll(any())).thenAnswer(inv -> {
            List<?> raw = inv.getArgument(0);
            return raw == null ? List.of() : raw.stream()
                    .map(value -> dev.mosaicast.core.tag.TagKeys.canonical((String) value))
                    .filter(tag -> !tag.isEmpty())
                    .distinct()
                    .toList();
        });
        lenient().when(refs.save(any(EpisodeRef.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(displays.save(any(EpisodeDisplay.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(displays.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private static RawEpisode raw(String guid, String title, Integer season, Integer episode) {
        return new RawEpisode(guid, title, "desc", "https://audio/" + guid,
                Instant.parse("2026-06-21T00:00:00Z"), season, episode, null,
                null, null, null, null, List.of(), Access.PUBLIC);
    }

    @Test
    void case1_newGuid_createsPublishedRefAndSnapshot() {
        when(refs.findByFeedId(FEED)).thenReturn(List.of());

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g1", "Pigeons", 2, 12)));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.withdrawn()).isZero();
        assertThat(result.bound()).isZero();
    }

    @Test
    void aPollRewritesOnlyTheTagsTheFeedOwns() {
        EpisodeRef known = EpisodeRef.published(FEED, "g1", 2, 11, "test-s02e11");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(known));

        reconciler.reconcile(FEED, "Test Feed", List.of(
                new RawEpisode("g1", "Pigeons", "desc", "https://audio/g1",
                        Instant.parse("2026-06-21T00:00:00Z"), 2, 12, null, null, null, null, null,
                        List.of("Maritime Lore", "maritime lore ", "Kraken"), Access.PUBLIC)));

        // Narrowed to the feed's own rows. The unqualified delete this replaced took a podcaster's manual
        // tag and a plugin's assignment with it at every poll — minutes, in practice.
        verify(tags).deleteByEpisodeRefIdAndSource(known.getId(), TagSource.FEED);

        // And the values are the vocabulary's canonical keys, deduplicated: three raw spellings, two tags.
        ArgumentCaptor<EpisodeTag> written = ArgumentCaptor.forClass(EpisodeTag.class);
        verify(tags, times(2)).save(written.capture());
        assertThat(written.getAllValues()).extracting(EpisodeTag::getTag)
                .containsExactlyInAnyOrder("maritime lore", "kraken");
        assertThat(written.getAllValues()).allSatisfy(
                tag -> assertThat(tag.getSource()).isEqualTo(TagSource.FEED));
    }

    @Test
    void aDuplicateGuidInOneBodyDoesNotLoseTheWholePoll() {
        // The second occurrence used to fall into the "new GUID" branch — byGuid holds only refs that
        // existed before the run — and the INSERT it scheduled violated uq_episode_ref_feed_guid. That
        // exception escaped the @Transactional reconcile, so *nothing* was written, not even the items
        // that parsed cleanly before the duplicate, and the feed stayed permanently empty (core#160).
        when(refs.findByFeedId(FEED)).thenReturn(List.of());

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(
                raw("g1", "Pigeons", 2, 12),
                raw("g1", "Pigeons, again", 2, 12),
                raw("g2", "Kraken", 2, 13)));

        // Counted once, and the item after it still lands.
        assertThat(result.created()).isEqualTo(2);
        verify(refs, times(2)).save(any(EpisodeRef.class));
    }

    @Test
    void anItemWithNoGuidAndNoLinkIsCountedRatherThanVanishing() {
        // `guid = entry.getUri() != null ? entry.getUri() : entry.getLink()` — both may be null, and the
        // reconciler simply continued, so a poll reported "N item(s) fetched — 0 new", which reads exactly
        // like "nothing changed" (core#184).
        when(refs.findByFeedId(FEED)).thenReturn(List.of());

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(
                raw(null, "Nameless", null, null),
                raw("g2", "Kraken", 2, 13)));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    void case2_knownGuid_refreshesWithoutCreating() {
        EpisodeRef known = EpisodeRef.published(FEED, "g1", 2, 11, "test-s02e11");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(known));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g1", "Pigeons v2", 2, 12)));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
        // Relation refreshed from the feed, identity preserved.
        assertThat(known.getEpisodeNo()).isEqualTo(12);
        assertThat(known.getStatus()).isEqualTo(EpisodeStatus.PUBLISHED);
    }

    @Test
    void case3_missingGuid_withdrawsNeverDeletes() {
        EpisodeRef gone = EpisodeRef.published(FEED, "g-old", 1, 3, "test-s01e03");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(gone));

        // Feed no longer lists g-old (a different item is present).
        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g-new", "New", 1, 4)));

        assertThat(result.withdrawn()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(gone.getStatus()).isEqualTo(EpisodeStatus.WITHDRAWN);
    }

    @Test
    void anEmptyChannelWithdrawsEveryEpisodeButDeletesNone() {
        // Pinned rather than guarded (core#191). A body with no items at all is indistinguishable here from a
        // show that removed everything, so the reconciler does what §5.2 says for each vanished GUID and
        // withdraws it. What makes that survivable when it was a publishing glitch is that it is reversible:
        // nothing is deleted, and the next poll that lists them revives the same refs (below).
        EpisodeRef one = EpisodeRef.published(FEED, "g-1", 1, 1, "test-s01e01");
        EpisodeRef two = EpisodeRef.published(FEED, "g-2", 1, 2, "test-s01e02");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(one, two));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of());

        assertThat(result.withdrawn()).isEqualTo(2);
        assertThat(one.getStatus()).isEqualTo(EpisodeStatus.WITHDRAWN);
        assertThat(two.getStatus()).isEqualTo(EpisodeStatus.WITHDRAWN);
        verify(refs, never()).delete(any());
        verify(refs, never()).deleteAll(any());
    }

    @Test
    void aWithdrawnEpisodeThatReappearsIsRevivedWithItsIdentityIntact() {
        // The revive branch in EpisodeRef.refreshFromFeed had no test: the same ref — same id, same slug, so
        // the same plugin data and the same links — comes back, rather than a new episode being created.
        EpisodeRef back = EpisodeRef.published(FEED, "g-back", 1, 5, "test-s01e05");
        back.withdraw();
        UUID id = back.getId();
        when(refs.findByFeedId(FEED)).thenReturn(List.of(back));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g-back", "Back", 1, 5)));

        assertThat(back.getStatus()).isEqualTo(EpisodeStatus.PUBLISHED);
        assertThat(back.getId()).isEqualTo(id);
        assertThat(back.getSlug()).isEqualTo("test-s01e05");
        assertThat(result.created()).isZero();
        assertThat(result.withdrawn()).isZero();
        // A revival changes what the public sees, so it counts even though the snapshot may be identical.
        assertThat(result.updated()).isEqualTo(1);
    }

    @Test
    void plannedBinding_exactSeasonEpisode_bindsPlannedToFeedItem() {
        EpisodeRef planned = EpisodeRef.planned(FEED, 2, 13,
                new DisplaySnapshot("Year in review", "", null, null, null, null, null, null, null), "test-s02e13");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(planned));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g-13", "Year in Review!", 2, 13)));

        assertThat(result.bound()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(planned.getStatus()).isEqualTo(EpisodeStatus.PUBLISHED);
        assertThat(planned.getExternalGuid()).isEqualTo("g-13");
        // Provisional display is dropped once the feed snapshot takes over (§4.3).
        assertThat(planned.getProvisionalDisplay()).isNull();
    }

    @Test
    void plannedBinding_noExactMatch_proposesFuzzySuggestionWithoutBinding() {
        EpisodeRef planned = EpisodeRef.planned(FEED, null, null,
                new DisplaySnapshot("The great coffee controversy", "", null, null, null, null, null, null, null),
                "test-coffee");
        when(refs.findByFeedId(FEED)).thenReturn(List.of(planned));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed",
                List.of(raw("g-x", "The Great Coffee Controversy (Part 1)", null, null)));

        // Created a fresh ref (no auto-merge) and left the planned ref PLANNED, with a suggestion.
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.bound()).isZero();
        assertThat(planned.getStatus()).isEqualTo(EpisodeStatus.PLANNED);
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).plannedRefId()).isEqualTo(planned.getId());
    }

    // ---- core#195: a changed feed body rewrote every item ----

    /** The snapshot the reconciler would build for {@code raw}, as if a previous poll had stored it. */
    private static EpisodeDisplay storedFor(EpisodeRef ref, RawEpisode raw) {
        return new EpisodeDisplay(ref.getId(), new dev.mosaicast.plugin.api.DisplaySnapshot(
                raw.title(), raw.description(), raw.audioUrl(), raw.publishedAt(), raw.declaredDuration(),
                raw.imageUrl(), raw.feedImageUrl(), raw.author(), raw.subtitle()));
    }

    @Test
    void anItemThatDidNotChangeWritesNothingAndIsNotCountedAsUpdated() {
        EpisodeRef known = EpisodeRef.published(FEED, "g1", 2, 11, "test-s02e11");
        RawEpisode same = new RawEpisode("g1", "Pigeons", "desc", "https://audio/g1",
                Instant.parse("2026-06-21T00:00:00Z"), 2, 11, null, null, null, null, null,
                List.of("Birds", "cities"), Access.PUBLIC);
        when(refs.findByFeedId(FEED)).thenReturn(List.of(known));
        when(displays.findById(known.getId())).thenReturn(Optional.of(storedFor(known, same)));
        when(tags.tagKeysFrom(known.getId(), dev.mosaicast.core.tag.TagSource.FEED))
                .thenReturn(List.of("cities", "birds"));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(same));

        assertThat(result.updated()).isZero();
        verify(displays, never()).save(any());
        verify(tags, never()).deleteByEpisodeRefIdAndSource(any(), any());
        verify(tags, never()).save(any());
        // Not even the vocabulary lookup a tag rewrite costs.
        verify(vocabulary, never()).ensureAll(any());
    }

    @Test
    void aChangedTitleIsWrittenAndCounted() {
        EpisodeRef known = EpisodeRef.published(FEED, "g1", 2, 11, "test-s02e11");
        RawEpisode before = raw("g1", "Pigeons", 2, 11);
        when(refs.findByFeedId(FEED)).thenReturn(List.of(known));
        when(displays.findById(known.getId())).thenReturn(Optional.of(storedFor(known, before)));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(raw("g1", "Pigeons, revised", 2, 11)));

        assertThat(result.updated()).isEqualTo(1);
        verify(displays).save(any());
        // The tags did not move, so they are left alone.
        verify(tags, never()).deleteByEpisodeRefIdAndSource(any(), any());
    }

    @Test
    void aChangedTagSetAloneIsRewrittenAndCounted() {
        EpisodeRef known = EpisodeRef.published(FEED, "g1", 2, 11, "test-s02e11");
        RawEpisode now = new RawEpisode("g1", "Pigeons", "desc", "https://audio/g1",
                Instant.parse("2026-06-21T00:00:00Z"), 2, 11, null, null, null, null, null,
                List.of("birds", "harbours"), Access.PUBLIC);
        when(refs.findByFeedId(FEED)).thenReturn(List.of(known));
        when(displays.findById(known.getId())).thenReturn(Optional.of(storedFor(known, now)));
        when(tags.tagKeysFrom(known.getId(), dev.mosaicast.core.tag.TagSource.FEED)).thenReturn(List.of("birds"));

        ReconcileResult result = reconciler.reconcile(FEED, "Test Feed", List.of(now));

        assertThat(result.updated()).isEqualTo(1);
        verify(displays, never()).save(any());
        verify(tags).deleteByEpisodeRefIdAndSource(known.getId(), dev.mosaicast.core.tag.TagSource.FEED);
    }
}
