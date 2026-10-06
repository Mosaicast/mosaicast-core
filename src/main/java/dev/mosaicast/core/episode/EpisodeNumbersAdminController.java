// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.episode.EpisodeNumbersService.EpisodeNumbers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Setting an episode's season and episode number by hand (ARCHITECTURE §4.4, core#264).
 *
 * <p>A <strong>PODCASTER</strong> capability, like pinning related episodes: numbering is an editorial
 * decision about an episode (§8.5). Enforced by the {@code /api/admin/episodes/**} rule in
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin/episodes/{slug}/numbers")
public class EpisodeNumbersAdminController {

    private final EpisodeNumbersService numbers;

    public EpisodeNumbersAdminController(EpisodeNumbersService numbers) {
        this.numbers = numbers;
    }

    /** The numbers to use from now on; either may be null, and both are set together. */
    public record NumbersRequest(Integer season, Integer episodeNo) {
    }

    @GetMapping
    public EpisodeNumbers get(@PathVariable String slug) {
        return numbers.get(slug);
    }

    /** Pins the numbers, so feed polls stop overwriting them. */
    @PutMapping
    public EpisodeNumbers pin(@PathVariable String slug, @RequestBody NumbersRequest request) {
        return numbers.pin(slug, request.season(), request.episodeNo());
    }

    /** Goes back to the feed's numbers. */
    @DeleteMapping
    public EpisodeNumbers unpin(@PathVariable String slug) {
        return numbers.unpin(slug);
    }
}
