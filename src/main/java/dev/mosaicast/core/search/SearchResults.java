// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.search;

import dev.mosaicast.core.episode.EpisodeSummary;
import java.util.List;

/**
 * What the site found, by source (ARCHITECTURE §6, SDK {@code SearchProvider}).
 *
 * <p>Sections rather than one ranked list, because ranking across sources is not solvable: a plugin's score
 * and Postgres {@code ts_rank} are not on one scale, and pretending otherwise gives an order nobody can
 * explain. The shape says so — episodes are typed as episodes, and every other section names the plugin it
 * came from.
 *
 * @param query    the query as the visitor typed it, echoed so a client can label the results
 * @param episodes core's own hits, best first
 * @param plugins  one section per plugin that answered, in plugin id order
 */
public record SearchResults(String query, List<EpisodeSummary> episodes, List<PluginSection> plugins) {

    /**
     * One plugin's section.
     *
     * @param pluginId the plugin's id — also the {@code /p/{id}/} namespace its hits live under
     * @param name     the plugin's manifest name, so the section has a heading a visitor recognises
     * @param hits     what it found, best first, as the plugin ranked it
     * @param timedOut whether the plugin ran out of its budget. Reported rather than hidden: "nothing
     *                 found" and "did not answer" are different answers, and a visitor who gets the first
     *                 when the second is true will conclude the content is not there
     */
    public record PluginSection(String pluginId, String name, List<Hit> hits, boolean timedOut) {
    }

    /**
     * One hit from a plugin, as the host serves it.
     *
     * @param href    the resolved URL under the plugin's own namespace — the host owns URL shapes, so a
     *                plugin returns a subpath and this is what it became
     * @param title   what to show
     * @param snippet a short excerpt, possibly empty
     */
    public record Hit(String href, String title, String snippet) {
    }
}
