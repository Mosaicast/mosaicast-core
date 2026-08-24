// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeTag;
import dev.mosaicast.core.episode.EpisodeTagRepository;
import dev.mosaicast.plugin.api.TagInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The site's shared tag vocabulary and everything written against it (ARCHITECTURE §6.1, SDK {@code Tags}).
 *
 * <p>Tags used to be one feed's keywords, rewritten per poll. They are now a vocabulary several writers
 * share — the feed, a podcaster, and any plugin that declares a {@code tags} block — which puts three rules
 * in one place rather than in each writer:
 *
 * <ul>
 *   <li><strong>The host owns the key.</strong> Callers send any spelling; {@link TagKeys} decides what it
 *       is. A vocabulary normalised on one path only is not normalised, so feed ingest goes through here
 *       too.</li>
 *   <li><strong>Every assignment carries a source.</strong> A writer may remove its own and nobody else's,
 *       which is what makes "the feed cannot delete a plugin's tag" enforceable rather than merely
 *       stated.</li>
 *   <li><strong>Nothing here deletes a vocabulary entry.</strong> Dropping the last assignment leaves the
 *       word behind: it is shared, so removing it is an admin's call, not a writer's.</li>
 * </ul>
 *
 * <p>Reads are filtered to publicly visible episodes — the same enabled-feed, non-WITHDRAWN rule the shell
 * and {@code FeedAccess} use. The plugin-facing surface adds nothing to it: what a plugin may read about
 * episodes is what its caller may read.
 */
@Service
public class TagService {

    /** The most {@code similar} entries any caller gets, whatever it asks for. */
    public static final int MAX_SIMILAR = 50;

    /** What {@code similar} returns when the caller expresses no preference. */
    public static final int DEFAULT_SIMILAR = 10;

    private final TagRepository vocabulary;
    private final EpisodeTagRepository episodeTags;
    private final PluginTagRepository pluginTags;
    private final EpisodeRefRepository refs;

    public TagService(TagRepository vocabulary, EpisodeTagRepository episodeTags,
                      PluginTagRepository pluginTags, EpisodeRefRepository refs) {
        this.vocabulary = vocabulary;
        this.episodeTags = episodeTags;
        this.pluginTags = pluginTags;
        this.refs = refs;
    }

    // ---------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------

    /**
     * The whole vocabulary with its reach, most used first.
     *
     * @param pluginId whose subject counts to report; every other number is site-wide
     */
    @Transactional(readOnly = true)
    public List<TagInfo> vocabularyFor(String pluginId) {
        return vocabulary.vocabularyFor(pluginId).stream()
                .map(row -> new TagInfo(row.getTag(), row.getLabel(),
                        (int) row.getEpisodes(), (int) row.getSubjects()))
                .toList();
    }

    /** The visible episodes carrying a tag, whoever tagged them. An unknown tag is an empty list. */
    @Transactional(readOnly = true)
    public List<String> episodesWith(String rawTag) {
        String tag = TagKeys.canonical(rawTag);
        return tag.isEmpty() ? List.of() : episodeTags.visibleSlugsWithTag(tag);
    }

    /** The tags on one visible episode. An unknown or hidden episode is an empty list, not an error. */
    @Transactional(readOnly = true)
    public List<String> tagsOn(String episodeSlug) {
        return episodeSlug == null ? List.of() : episodeTags.visibleTagsOnSlug(episodeSlug);
    }

    /**
     * Tags that co-occur with this one across visible episodes, best first.
     *
     * <p>The ranking is the host's and deliberately outside the plugin contract — the SDK documents the order
     * as advice, so this can change without a contract bump.
     */
    @Transactional(readOnly = true)
    public List<TagInfo> similarTo(String rawTag, int limit, String pluginId) {
        String tag = TagKeys.canonical(rawTag);
        if (tag.isEmpty()) {
            return List.of();
        }
        int bounded = limit <= 0 ? DEFAULT_SIMILAR : Math.min(limit, MAX_SIMILAR);
        return vocabulary.similarTo(tag, pluginId, bounded).stream()
                .map(row -> new TagInfo(row.getTag(), row.getLabel(),
                        (int) row.getEpisodes(), (int) row.getSubjects()))
                .toList();
    }

    /** One plugin's own subjects carrying a tag. */
    @Transactional(readOnly = true)
    public List<String> subjectsWith(String pluginId, String rawTag) {
        String tag = TagKeys.canonical(rawTag);
        return tag.isEmpty() ? List.of() : pluginTags.subjectsWith(pluginId, tag);
    }

    /** The tags on one of a plugin's subjects; an unknown subject is an empty list. */
    @Transactional(readOnly = true)
    public List<String> tagsOnSubject(String pluginId, String subjectKey) {
        return subjectKey == null || subjectKey.isBlank()
                ? List.of() : pluginTags.tagsOnSubject(pluginId, subjectKey);
    }

    /** The display label for a canonical key, falling back to the key itself for an unknown tag. */
    @Transactional(readOnly = true)
    public String labelFor(String rawTag) {
        String tag = TagKeys.canonical(rawTag);
        return vocabulary.findById(tag).map(Tag::getLabel).orElse(tag);
    }

    // ---------------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------------

    /**
     * Tags one of a plugin's own subjects, adding the word to the vocabulary if it is new. Idempotent.
     *
     * @return the canonical key that was written, so a caller migrating a private tag column can store it
     * @throws IllegalArgumentException if the subject key or the tag carries nothing
     */
    @Transactional
    public String tagSubject(String pluginId, String subjectKey, String rawTag) {
        requireSubject(subjectKey);
        String tag = ensure(rawTag);
        PluginTag.Key key = new PluginTag.Key(pluginId, subjectKey, tag);
        if (!pluginTags.existsById(key)) {
            pluginTags.save(new PluginTag(pluginId, subjectKey, tag));
        }
        return tag;
    }

    /** Removes a tag from one of a plugin's subjects. Idempotent; the vocabulary entry stays. */
    @Transactional
    public void untagSubject(String pluginId, String subjectKey, String rawTag) {
        String tag = TagKeys.canonical(rawTag);
        if (subjectKey == null || subjectKey.isBlank() || tag.isEmpty()) {
            return;
        }
        pluginTags.deleteById(new PluginTag.Key(pluginId, subjectKey, tag));
    }

    /**
     * Tags a visible episode as {@code source}, adding the word to the vocabulary if it is new. Idempotent
     * and additive: an episode already carrying the tag from the feed keeps that row and gains this one.
     *
     * @return the canonical key that was written
     * @throws IllegalArgumentException if the tag carries nothing or the episode is unknown
     */
    @Transactional
    public String tagEpisode(String episodeSlug, String rawTag, String source) {
        UUID refId = requireVisibleEpisode(episodeSlug);
        String tag = ensure(rawTag);
        EpisodeTag.Key key = new EpisodeTag.Key(refId, tag, source);
        if (!episodeTags.existsById(key)) {
            episodeTags.save(new EpisodeTag(refId, tag, source));
        }
        return tag;
    }

    /**
     * Removes <strong>one source's</strong> tag assignment from an episode. Idempotent.
     *
     * <p>Only that source's row: if the feed also put this tag there, the episode keeps carrying it. That is
     * the promise the SDK makes plugins, and the {@code source} column is what turns it into something the
     * host enforces.
     */
    @Transactional
    public void untagEpisode(String episodeSlug, String rawTag, String source) {
        String tag = TagKeys.canonical(rawTag);
        if (tag.isEmpty()) {
            return;
        }
        refs.findVisibleBySlug(episodeSlug)
                .ifPresent(ref -> episodeTags.deleteAssignment(ref.getId(), tag, source));
    }

    /**
     * The canonical keys for a feed's raw values, with the vocabulary extended to cover them.
     *
     * <p>Feed ingest goes through the same normalisation as everything else, which is the point: a
     * vocabulary normalised on the plugin path only would fragment on the path that produces most of it.
     * Unusable values (blank, whitespace) are dropped rather than stored as entries nobody could name.
     */
    @Transactional
    public List<String> ensureAll(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (TagKeys.isUsable(raw)) {
                keys.add(ensure(raw));
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * Adds a word to the vocabulary if it is new, keeping the caller's spelling as the display label.
     *
     * @return the canonical key
     * @throws IllegalArgumentException if the spelling carries nothing
     */
    @Transactional
    public String ensure(String rawTag) {
        String tag = TagKeys.canonical(rawTag);
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("a tag needs at least one non-whitespace character");
        }
        if (!vocabulary.existsById(tag)) {
            vocabulary.save(new Tag(tag, TagKeys.label(rawTag)));
        }
        return tag;
    }

    /**
     * Drops every assignment a plugin ever made, on its own subjects and on episodes.
     *
     * <p>Called when an operator purges a plugin's data. The vocabulary entries stay: they are the site's,
     * and a word other episodes still carry is not the departing plugin's to take with it.
     *
     * @return how many assignments were removed
     */
    @Transactional
    public int purgePlugin(String pluginId) {
        return pluginTags.deleteByPluginId(pluginId)
                + episodeTags.deleteBySource(TagSource.plugin(pluginId));
    }

    private UUID requireVisibleEpisode(String slug) {
        Optional<EpisodeRef> ref = slug == null ? Optional.empty() : refs.findVisibleBySlug(slug);
        return ref.map(EpisodeRef::getId)
                .orElseThrow(() -> new IllegalArgumentException("no such episode: " + slug));
    }

    /**
     * A subject key is the plugin's to invent, with one constraint the host cannot lift.
     *
     * <p>The HTTP surface carries it as a path segment, and Spring Security's default firewall rejects an
     * encoded {@code /} before any handler runs — so a key containing a slash would fail as an unexplained
     * 400 from the network layer. Saying so here means a plugin author learns it from the message rather
     * than from a status code with no body, and the in-process Java surface refuses the same keys the HTTP
     * one does rather than accepting keys that only work from the backend.
     */
    private static void requireSubject(String subjectKey) {
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("a subject key cannot be blank");
        }
        if (subjectKey.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "a subject key may not contain '/': " + subjectKey + " (use another separator, e.g. ':')");
        }
    }
}
