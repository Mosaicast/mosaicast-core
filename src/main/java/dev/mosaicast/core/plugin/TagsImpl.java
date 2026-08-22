// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.tag.TagService;
import dev.mosaicast.core.tag.TagSource;
import dev.mosaicast.plugin.api.TagInfo;
import dev.mosaicast.plugin.api.Tags;
import java.util.List;

/**
 * Host-side {@link Tags} for one plugin (ARCHITECTURE §6.1, §7.4): the shared vocabulary, plus that
 * plugin's own assignments against it.
 *
 * <p>Every write is attributed to {@code plugin:<id>}, which is what makes the contract's three refusals
 * enforceable rather than merely stated — a plugin removes its own assignment and nobody else's, cannot drop
 * a word from the vocabulary, and cannot rename one.
 *
 * <p>{@code writesEpisodes} is read off the manifest and nowhere else. Without it the two episode methods
 * throw {@link UnsupportedOperationException}: the SDK documents a refusal, and a silently dropped write
 * would leave a plugin believing it had tagged something the shell will never show.
 */
public class TagsImpl implements Tags {

    private final String pluginId;
    private final boolean readsVocabulary;
    private final boolean writesEpisodes;
    private final TagService tags;

    public TagsImpl(PluginManifest manifest, TagService tags) {
        this.pluginId = manifest.id();
        this.readsVocabulary = manifest.readsTagVocabulary();
        this.writesEpisodes = manifest.writesEpisodeTags();
        this.tags = tags;
    }

    @Override
    public List<TagInfo> all() {
        requireReads();
        return tags.vocabularyFor(pluginId);
    }

    @Override
    public List<String> episodesWith(String tag) {
        requireReads();
        return tags.episodesWith(tag);
    }

    @Override
    public List<String> tagsOn(String episodeSlug) {
        requireReads();
        return tags.tagsOn(episodeSlug);
    }

    @Override
    public List<TagInfo> similarTo(String tag, int limit) {
        requireReads();
        return tags.similarTo(tag, limit, pluginId);
    }

    @Override
    public List<String> subjectsWith(String tag) {
        requireReads();
        return tags.subjectsWith(pluginId, tag);
    }

    @Override
    public List<String> tagsOnSubject(String subjectKey) {
        requireReads();
        return tags.tagsOnSubject(pluginId, subjectKey);
    }

    @Override
    public void tagSubject(String subjectKey, String tag) {
        tags.tagSubject(pluginId, subjectKey, tag);
    }

    @Override
    public void untagSubject(String subjectKey, String tag) {
        tags.untagSubject(pluginId, subjectKey, tag);
    }

    @Override
    public void tagEpisode(String episodeSlug, String tag) {
        requireEpisodeWrites();
        tags.tagEpisode(episodeSlug, tag, TagSource.plugin(pluginId));
    }

    @Override
    public void untagEpisode(String episodeSlug, String tag) {
        requireEpisodeWrites();
        tags.untagEpisode(episodeSlug, tag, TagSource.plugin(pluginId));
    }

    private void requireEpisodeWrites() {
        if (!writesEpisodes) {
            throw new UnsupportedOperationException(
                    "plugin '%s' did not declare tags.writesEpisodes — tagging an episode changes the shell's "
                            .formatted(pluginId)
                            + "filter options and what core recommends, so it is opt-in (§6.1)");
        }
    }

    /**
     * Reads are refused for the same reason writes are: the manifest is the whole story.
     *
     * <p>A plugin can only reach this by declaring {@code readsVocabulary: false} and then reading anyway —
     * the flag defaults to true — so the refusal names the contradiction rather than pretending the
     * vocabulary is empty.
     */
    private void requireReads() {
        if (!readsVocabulary) {
            throw new UnsupportedOperationException(
                    "plugin '%s' declared tags.readsVocabulary: false".formatted(pluginId));
        }
    }
}
