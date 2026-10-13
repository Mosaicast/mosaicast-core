// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.legal.LegalPageRepository;
import dev.mosaicast.core.plugin.PluginLoaderService;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Whether a counted subject exists (ARCHITECTURE §19). Anything else is dropped, so an invented slug cannot
 * grow the table. Answers are cached briefly: a launch-day burst asks about the same few episodes.
 */
@Component
public class SubjectValidator {

    private static final Set<String> FIXED = Set.of("home", "about", "search");
    /** The slug grammar of feeds, episodes, legal pages and plugin ids: lower-case runs joined by hyphens. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int SLUG_MAX = 120;

    private final EpisodeRefRepository episodes;
    private final FeedRepository feeds;
    private final LegalPageRepository legal;
    private final PluginLoaderService plugins;
    private final Cache<String, Boolean> known = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    public SubjectValidator(EpisodeRefRepository episodes, FeedRepository feeds, LegalPageRepository legal,
                            PluginLoaderService plugins) {
        this.episodes = episodes;
        this.feeds = feeds;
        this.legal = legal;
        this.plugins = plugins;
    }

    /** Whether {@code subject} may be counted for {@code kind}. */
    public boolean accepts(String kind, String subject) {
        if (subject == null || subject.length() > SLUG_MAX + 10) {
            return false;
        }
        boolean episodeOnly = !"view".equals(kind);
        if (FIXED.contains(subject)) {
            return !episodeOnly;
        }
        int slash = subject.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String type = subject.substring(0, slash);
        String slug = subject.substring(slash + 1);
        if (slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
            return false;
        }
        if (episodeOnly && !"episode".equals(type)) {
            return false;
        }
        return switch (type) {
            case "episode" -> known.get(subject, s -> episodes.existsBySlug(slug));
            case "feed" -> known.get(subject, s -> feeds.existsBySlug(slug));
            case "legal" -> known.get(subject, s -> legal.existsBySlug(slug));
            case "page" -> plugins.active(slug).isPresent();
            default -> false;
        };
    }
}
