// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
}
