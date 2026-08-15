// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curating pinned related episodes (ARCHITECTURE §6.3).
 *
 * <p>A <strong>PODCASTER</strong> capability, not an admin-only one: §8.5 puts episodes and their editorial
 * decisions with the podcaster, and "which episode belongs next to this one" is exactly that kind of
 * decision. Enforced by the {@code /api/admin/episodes/**} rule in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin/episodes/{slug}/pins")
public class EpisodePinAdminController {

    private final EpisodePinService pins;

    public EpisodePinAdminController(EpisodePinService pins) {
        this.pins = pins;
    }

    /** Which episode to pin, by its public slug — the same identifier the admin sees in the URL bar. */
    public record PinRequest(String relatedSlug) {
    }

    @GetMapping
    public List<EpisodeSummary> list(@PathVariable String slug) {
        return pins.list(slug);
    }

    /** Adds a pin and returns the new list, so the caller never has to re-read to render the result. */
    @PostMapping
    public List<EpisodeSummary> pin(@PathVariable String slug, @RequestBody PinRequest request) {
        return pins.pin(slug, request.relatedSlug());
    }

    @DeleteMapping("/{relatedSlug}")
    public List<EpisodeSummary> unpin(@PathVariable String slug, @PathVariable String relatedSlug) {
        return pins.unpin(slug, relatedSlug);
    }
}
