// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.List;
import java.util.UUID;

/**
 * "What else should someone who just heard this episode see?" (ARCHITECTURE §6.3).
 *
 * <p><strong>Core, and a swappable strategy — not a plugin.</strong> The sidebar that renders this is part
 * of the shell and must work with zero plugins installed, so the extension point is an interface the host
 * resolves, not a slot a plugin fills. §6.3 names the v2 successor already: an embedding strategy over
 * {@code pgvector}, or a recommender that overrides the provider. Both replace {@link DefaultRelatedProvider}
 * without the widget or the endpoint changing, which is the whole reason this is an interface at one method
 * rather than a service with the strategy inlined.
 *
 * <p><strong>Related is not sequential navigation.</strong> Previous/next (§6.2) is core navigation, always
 * shown, and answers "what came before and after". This answers "what else is like this", may legitimately
 * return nothing, and is never a substitute. They are rendered as separate things on purpose.
 *
 * <p>Because it runs over {@link EpisodeRef} ids rather than feed rows, any implementation automatically
 * inherits the host's visibility and — when v2 brings it — tier gating. A provider never decides who may
 * see what; it decides what is relevant, from the set it is handed.
 */
public interface RelatedProvider {

    /**
     * Episodes related to the given one, best first.
     *
     * @param episodeRefId the episode being viewed
     * @param limit        the most to return; implementations may return fewer, including none
     * @return related episode ids, best first, never containing {@code episodeRefId} itself
     */
    List<UUID> related(UUID episodeRefId, int limit);

    /**
     * Drops any cached answers.
     *
     * <p>Called when the episode set changes — a poll that adds episodes changes what "related" means for
     * the ones already there, so a cache that survived it would keep serving an answer computed against a
     * smaller catalogue.
     */
    void invalidate();
}
