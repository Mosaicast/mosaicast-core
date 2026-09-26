// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Display snapshots for a plugin's frontend (ARCHITECTURE §6.1/§7.5) — the endpoint behind {@code ctx.feeds},
 * and the frontend half of the backend's {@link dev.mosaicast.plugin.api.FeedAccess}.
 *
 * <p>The Java contract could read a snapshot and the frontend could not, so a plugin that wanted to draw an
 * episode card copied host data into its own doc store and kept it fresh on a schedule — a backend, an
 * ingest, a reserved key and a copy that was stale between runs, for fields the host already had.
 *
 * <p><strong>No read floor of its own</strong>, unlike every other plugin data surface. This returns host
 * data the same visitor can read from {@code /api/episodes/*}; it exists so a plugin need not know that URL
 * shape, which is the argument {@code ctx.links} already makes. What it must not become is a way to see more
 * than the visitor can, so:
 *
 * <ul>
 *   <li><strong>The host filters, the plugin consumes.</strong> A WITHDRAWN episode, or one in a disabled
 *       feed, is <em>absent</em> from the answer rather than present and redacted.</li>
 *   <li><strong>Absence is not explained.</strong> A slug nobody ever minted and a slug the visitor may not
 *       see produce the same missing key — distinguishing them would confirm the existence of an episode
 *       they were not shown.</li>
 *   <li><strong>The batch is clamped, not rejected</strong> (200, mirroring {@code scope-episodes}): a
 *       plugin asking about more episodes than the host answers for in one call gets an answer.</li>
 * </ul>
 *
 * <p>Timestamps go out as ISO-8601 strings, which is what the SDK's mirrored {@code DisplaySnapshot}
 * documents. That is why this projects into its own record rather than serialising the contract type: the
 * host's {@code ObjectMapper} writes an {@code Instant} as an epoch number, which is right for the doc store
 * and wrong here, and a snapshot whose shape depends on a global Jackson setting is a contract nobody owns.
 */
@RestController
public class PluginEpisodeController {

    /** The most slugs answered in one call — the SDK exports the same number as {@code DISPLAY_BATCH_LIMIT}. */
    public static final int MAX_SLUGS = 200;

    private final PluginLoaderService plugins;
    private final EpisodeQueryService episodes;

    public PluginEpisodeController(PluginLoaderService plugins, EpisodeQueryService episodes) {
        this.plugins = plugins;
        this.episodes = episodes;
    }

    /**
     * The display snapshots for the named slugs, keyed by slug.
     *
     * @param slugs comma-separated public episode slugs; beyond {@link #MAX_SLUGS} the rest are ignored
     */
    @GetMapping("/api/plugins/{id}/episodes")
    public Map<String, EpisodeDisplayView> displays(@PathVariable String id,
                                                    @RequestParam(defaultValue = "") String slugs) {
        requireActivePlugin(id);
        Map<String, EpisodeDisplayView> answer = new LinkedHashMap<>();
        List<String> asked = parseSlugs(slugs);
        if (asked.isEmpty()) {
            return answer;
        }
        // One query for the batch: a plugin drawing twenty cards should cost one round trip, which is the
        // whole reason displayMany exists beside display. Visibility is resolved by the same query the
        // shell uses, so an episode this caller may not see never reaches the map.
        Map<String, DisplaySnapshot> visible = episodes.visibleDisplaysBySlug(asked);
        for (String slug : asked) {
            DisplaySnapshot snapshot = visible.get(slug);
            if (snapshot != null) {
                answer.put(slug, EpisodeDisplayView.from(snapshot));
            }
        }
        return answer;
    }

    /** The requested slugs: trimmed, de-duplicated, in request order, and clamped to {@link #MAX_SLUGS}. */
    private static List<String> parseSlugs(String slugs) {
        List<String> asked = new ArrayList<>();
        if (slugs.isBlank()) {
            return asked;
        }
        for (String raw : slugs.split(",")) {
            String slug = raw.strip();
            if (slug.isEmpty() || asked.contains(slug)) {
                continue;
            }
            asked.add(slug);
            if (asked.size() == MAX_SLUGS) {
                break;
            }
        }
        return asked;
    }

    /**
     * A loaded, switched-on plugin — 404 otherwise, as on every other plugin surface (§7.8).
     *
     * <p>The plugin is checked even though the data is not the plugin's: {@code /api/plugins/<id>/…} is that
     * plugin's namespace, and a disabled plugin's namespace answering normally would be the one place a
     * switched-off plugin still worked.
     */
    private void requireActivePlugin(String id) {
        plugins.active(id).orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
    }

    /**
     * One episode's presentation, as the SDK's mirrored {@code DisplaySnapshot} documents it: optional
     * fields absent when the feed declares nothing, {@code publishedAt} an ISO-8601 instant and
     * {@code duration} an ISO-8601 duration.
     *
     * <p>{@code description} is the feed's HTML verbatim — untrusted, and documented as such in the SDK;
     * {@code descriptionText} is the same prose as plain text (SDK 0.16.0), never absent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EpisodeDisplayView(String title, String description, String audioUrl, String publishedAt,
                                     String duration, String imageUrl, String feedImageUrl, String author,
                                     String subtitle, String descriptionText) {

        static EpisodeDisplayView from(DisplaySnapshot snapshot) {
            return new EpisodeDisplayView(
                    snapshot.title(),
                    snapshot.description(),
                    snapshot.audioUrl(),
                    snapshot.publishedAt() == null ? null : snapshot.publishedAt().toString(),
                    snapshot.duration() == null ? null : snapshot.duration().toString(),
                    snapshot.imageUrl(),
                    snapshot.feedImageUrl(),
                    snapshot.author(),
                    snapshot.subtitle(),
                    snapshot.descriptionText());
        }
    }
}
