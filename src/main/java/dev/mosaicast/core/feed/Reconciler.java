// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeSlug;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.episode.EpisodeTagRepository;
import dev.mosaicast.core.episode.EpisodeTag;
import dev.mosaicast.core.episode.RelatedProvider;
import dev.mosaicast.core.tag.TagService;
import dev.mosaicast.core.tag.TagSource;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns raw feed items into {@link EpisodeRef}s + display snapshots (ARCHITECTURE §5.2/§5.3), the
 * pipeline's core risk point. Three GUID cases plus PLANNED binding, on the same merge machinery:
 *
 * <ol>
 *   <li><b>New GUID</b> → create a PUBLISHED ref (identity) + snapshot (presentation).</li>
 *   <li><b>Known GUID</b> → refresh season relation + overwrite the snapshot; plugin data untouched.</li>
 *   <li><b>Ref's GUID now missing</b> → set WITHDRAWN, never hard-delete (plugin data would orphan).</li>
 * </ol>
 *
 * <p>Before creating for a new GUID, a PLANNED ref matching the item's <em>declared season/episode</em>
 * is auto-bound (PLANNED→PUBLISHED); a fuzzy-title match is only proposed as a {@link ReconcileResult.Suggestion}
 * for the podcaster to confirm — never merged silently.
 */
@Service
public class Reconciler {

    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final EpisodeTagRepository tags;
    private final TagService vocabulary;
    private final RelatedProvider related;

    public Reconciler(EpisodeRefRepository refs, EpisodeDisplayRepository displays,
                      EpisodeTagRepository tags, TagService vocabulary, RelatedProvider related) {
        this.refs = refs;
        this.displays = displays;
        this.tags = tags;
        this.vocabulary = vocabulary;
        this.related = related;
    }

    @Transactional
    public ReconcileResult reconcile(UUID feedId, String feedTitle, List<RawEpisode> rawEpisodes) {
        List<EpisodeRef> existing = refs.findByFeedId(feedId);

        Map<String, EpisodeRef> byGuid = new HashMap<>();
        List<EpisodeRef> planned = new ArrayList<>();
        for (EpisodeRef ref : existing) {
            if (ref.getExternalGuid() != null) {
                byGuid.put(ref.getExternalGuid(), ref);
            }
            if (ref.getStatus() == EpisodeStatus.PLANNED) {
                planned.add(ref);
            }
        }

        int created = 0;
        int updated = 0;
        int bound = 0;
        Set<String> seenGuids = new HashSet<>();
        List<ReconcileResult.Suggestion> suggestions = new ArrayList<>();

        for (RawEpisode raw : rawEpisodes) {
            if (raw.externalGuid() == null) {
                continue; // a source that yields no stable id cannot be reconciled by GUID
            }
            seenGuids.add(raw.externalGuid());

            EpisodeRef known = byGuid.get(raw.externalGuid());
            if (known != null) {
                // Case 2: known GUID — refresh relations, overwrite the snapshot. Identity untouched.
                known.refreshFromFeed(raw.season(), raw.episodeNumber());
                refs.save(known);
                upsertDisplay(known.getId(), raw);
                updated++;
                continue;
            }

            // New GUID: try PLANNED binding by exact declared season/episode before creating (§5.3).
            EpisodeRef match = findExactPlanned(planned, raw);
            if (match != null) {
                match.bindToFeedItem(raw.externalGuid(), raw.season(), raw.episodeNumber());
                refs.save(match);
                upsertDisplay(match.getId(), raw);
                planned.remove(match);
                byGuid.put(raw.externalGuid(), match);
                bound++;
                continue;
            }

            // No exact planned match: create a new PUBLISHED ref (case 1) and propose any fuzzy bindings.
            collectFuzzySuggestions(planned, raw, suggestions);
            String slug = EpisodeSlug.generate(
                    feedTitle, raw.season(), raw.episodeNumber(), raw.title(), refs::existsBySlug);
            EpisodeRef fresh =
                    EpisodeRef.published(feedId, raw.externalGuid(), raw.season(), raw.episodeNumber(), slug);
            refs.save(fresh);
            upsertDisplay(fresh.getId(), raw);
            created++;
        }

        // Case 3: refs whose GUID disappeared from the feed → WITHDRAWN (never hard-deleted).
        int withdrawn = 0;
        for (EpisodeRef ref : existing) {
            if (ref.getExternalGuid() != null
                    && ref.getStatus() != EpisodeStatus.WITHDRAWN
                    && !seenGuids.contains(ref.getExternalGuid())) {
                ref.withdraw();
                refs.save(ref);
                withdrawn++;
            }
        }

        // A poll that reaches here found a changed feed (an unchanged one is a 304 and never gets this far,
        // §5.4). Any of it can change what "related" means: a new episode is a new candidate for every
        // episode that shares a tag with it, a withdrawal removes one, and re-written tags or titles move
        // the scores. Working out *which* cached answers moved costs more than dropping them (§6.3).
        related.invalidate();

        return new ReconcileResult(created, updated, withdrawn, bound, suggestions);
    }

    /** Finds a PLANNED ref whose declared season AND episode number both equal the raw item's (§5.3). */
    private static EpisodeRef findExactPlanned(List<EpisodeRef> planned, RawEpisode raw) {
        if (raw.season() == null || raw.episodeNumber() == null) {
            return null;
        }
        for (EpisodeRef ref : planned) {
            if (raw.season().equals(ref.getSeason()) && raw.episodeNumber().equals(ref.getEpisodeNo())) {
                return ref;
            }
        }
        return null;
    }

    /** Proposes (does not apply) fuzzy-title bindings for the still-unbound PLANNED refs (§5.3). */
    private static void collectFuzzySuggestions(
            List<EpisodeRef> planned, RawEpisode raw, List<ReconcileResult.Suggestion> out) {
        for (EpisodeRef ref : planned) {
            DisplaySnapshot provisional = ref.getProvisionalDisplay();
            if (provisional == null) {
                continue;
            }
            double similarity = TitleSimilarity.similarity(provisional.title(), raw.title());
            if (similarity >= TitleSimilarity.DEFAULT_THRESHOLD) {
                out.add(new ReconcileResult.Suggestion(
                        ref.getId(), provisional.title(), raw.externalGuid(), raw.title(), similarity));
            }
        }
    }

    /** Writes the feed's presentation snapshot and tags for a ref, overwriting any existing ones (§4.2). */
    private void upsertDisplay(UUID refId, RawEpisode raw) {
        DisplaySnapshot snapshot = new DisplaySnapshot(
                raw.title(), raw.description(), raw.audioUrl(), raw.publishedAt(), raw.declaredDuration(),
                raw.imageUrl(), raw.feedImageUrl(), raw.author(), raw.subtitle());
        EpisodeDisplay display = displays.findById(refId)
                .map(existing -> {
                    existing.overwrite(snapshot);
                    return existing;
                })
                .orElseGet(() -> new EpisodeDisplay(refId, snapshot));
        displays.save(display);
        upsertTags(refId, raw.tags());
    }

    /**
     * Replaces the tags <strong>this feed owns</strong> on an episode with its current set (§6.1).
     *
     * <p>Overwrite semantics still, because the feed's keywords are presentation and the feed is their only
     * authority — but scoped to {@code source = feed}. The unqualified wipe this replaced meant a tag a
     * podcaster or a plugin added lasted until the next poll, which is to say it did not last at all.
     *
     * <p>Values go through {@link TagService#ensureAll} rather than being stored verbatim: the vocabulary is
     * shared, and one normalised on the plugin path only would fragment on the path that produces most of
     * it.
     */
    private void upsertTags(UUID refId, List<String> tagValues) {
        List<String> canonical = vocabulary.ensureAll(tagValues);
        tags.deleteByEpisodeRefIdAndSource(refId, TagSource.FEED);
        for (String tag : canonical) {
            tags.save(new EpisodeTag(refId, tag, TagSource.FEED));
        }
    }
}
