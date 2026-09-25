// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.mosaicast.core.episode.EpisodeDetail;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.log.LogSafe;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A listener's browser reporting that it could not play an episode (core#170).
 *
 * <p>An enclosure that moved, a host that is down, a mixed-content block — all of them fail in the listener's
 * browser and nowhere else. The feed poll still succeeds, because the XML is fine, so nothing the server does
 * on its own ever notices. This is how the operator hears about it: one WARN under the {@code feed}
 * subsystem, which is what Logs &amp; health counts.
 *
 * <p><strong>Open to anonymous callers, and bounded so that is safe.</strong> Most listeners are not signed
 * in, and they are exactly the ones who meet a dead enclosure. What bounds it is that one episode produces at
 * most one line per {@link #QUIET_PERIOD}, however many browsers report it — the log grows with the number of
 * episodes, never with the number of requests. The line says it is a browser's report, since nothing here
 * verifies it, and it carries no detail a caller chose beyond a four-valued code.
 */
@RestController
public class PlaybackErrorController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackErrorController.class);

    /** How long one episode's report stands for all later ones. */
    static final Duration QUIET_PERIOD = Duration.ofMinutes(15);

    private final EpisodeQueryService episodes;
    private final FeedService feeds;
    private final Cache<UUID, Boolean> recentlyReported = Caffeine.newBuilder()
            .expireAfterWrite(QUIET_PERIOD)
            .maximumSize(10_000)
            .build();

    public PlaybackErrorController(EpisodeQueryService episodes, FeedService feeds) {
        this.episodes = episodes;
        this.feeds = feeds;
    }

    /**
     * What the browser said: the {@code MediaError.code} — 1 aborted, 2 network, 3 decode, 4 source not
     * supported — or 0 when it gave none.
     *
     * @param code the media error code
     */
    public record Report(int code) {
    }

    /**
     * Records that a browser could not play this episode.
     *
     * @param slug   the episode's public slug; an unknown or hidden one is a 404, like every episode read
     * @param report the error code
     * @return 204, whether or not this report was the one that got logged
     */
    @PostMapping("/api/episodes/{slug}/playback-error")
    public ResponseEntity<Void> report(@PathVariable String slug, @RequestBody Report report) {
        if (report == null || report.code() < 0 || report.code() > 4) {
            throw new IllegalArgumentException("code must be a MediaError code, 0 to 4");
        }
        EpisodeDetail episode = episodes.detailBySlug(slug);
        if (recentlyReported.asMap().putIfAbsent(episode.id(), Boolean.TRUE) == null) {
            log.warn("A listener's browser could not play '{}' from '{}' ({}). The audio file may have moved "
                            + "or its host be unreachable; this is the browser's report, not checked by this server.",
                    LogSafe.of(episode.title()), LogSafe.of(feedTitle(episode)), describe(report.code()));
        }
        return ResponseEntity.noContent().build();
    }

    private String feedTitle(EpisodeDetail episode) {
        try {
            return feeds.get(episode.feedId()).title();
        } catch (RuntimeException e) {
            return String.valueOf(episode.feedId());
        }
    }

    /** The code as the name an operator can search for. */
    static String describe(int code) {
        return switch (code) {
            case 1 -> "MEDIA_ERR_ABORTED";
            case 2 -> "MEDIA_ERR_NETWORK";
            case 3 -> "MEDIA_ERR_DECODE";
            case 4 -> "MEDIA_ERR_SRC_NOT_SUPPORTED";
            default -> "no error code";
        };
    }
}
