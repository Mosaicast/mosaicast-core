// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.tag.TagService;
import dev.mosaicast.core.tag.TagSource;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.TagInfo;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-plugin HTTP surface for the site's shared tag vocabulary (ARCHITECTURE §6.1) — the endpoints
 * behind {@code ctx.tags}, mirroring the Java {@link dev.mosaicast.plugin.api.Tags} one-to-one.
 *
 * <p>Two gates, in this order. A plugin whose manifest declares no {@code tags} block has no surface at all
 * and gets a <strong>404</strong>, indistinguishable from an unknown plugin — the same answer the blob and
 * schema surfaces give. Past that, reads and writes take the plugin's declared {@code data} floors, because
 * a tag assignment is plugin data like any other and a second set of floors would be a second thing to keep
 * in step.
 *
 * <p>The <em>episode</em> writes need one more thing: {@code tags.writesEpisodes}. Tagging an episode
 * changes what the shell offers as a filter and what core recommends beside it, so it is a capability an
 * operator can read off the manifest rather than a convenience — and its absence is a <strong>403</strong>
 * with a reason, not a silently dropped write.
 *
 * <p>Reads are filtered to episodes the caller may see, as everywhere else on the plugin surface: the host
 * resolves visibility and the plugin consumes it.
 */
@RestController
public class PluginTagController {

    private final PluginLoaderService plugins;
    private final TagService tags;

    public PluginTagController(PluginLoaderService plugins, TagService tags) {
        this.plugins = plugins;
        this.tags = tags;
    }

    /** The whole site vocabulary with its reach: episodes site-wide, subjects only this plugin's own. */
    @GetMapping("/api/plugins/{id}/tags")
    public List<TagInfo> vocabulary(@PathVariable String id, Authentication authentication) {
        PluginManifest manifest = readable(id, authentication);
        return tags.vocabularyFor(manifest.id());
    }

    /** The visible episodes carrying a tag, whoever tagged them. An unknown tag is an empty list. */
    @GetMapping("/api/plugins/{id}/tags/{tag}/episodes")
    public List<String> episodesWith(@PathVariable String id, @PathVariable String tag,
                                     Authentication authentication) {
        readable(id, authentication);
        return tags.episodesWith(tag);
    }

    /** This plugin's own subjects carrying a tag — there is no way to ask about another plugin's. */
    @GetMapping("/api/plugins/{id}/tags/{tag}/subjects")
    public List<String> subjectsWith(@PathVariable String id, @PathVariable String tag,
                                     Authentication authentication) {
        PluginManifest manifest = readable(id, authentication);
        return tags.subjectsWith(manifest.id(), tag);
    }

    /**
     * Tags that co-occur with this one, best first.
     *
     * <p>The order is the host's and outside the contract, so treat it as advice; {@code limit} is clamped
     * rather than rejected, matching the other bounded plugin reads.
     */
    @GetMapping("/api/plugins/{id}/tags/{tag}/similar")
    public List<TagInfo> similar(@PathVariable String id, @PathVariable String tag,
                                 @RequestParam(defaultValue = "10") int limit,
                                 Authentication authentication) {
        PluginManifest manifest = readable(id, authentication);
        return tags.similarTo(tag, limit, manifest.id());
    }

    /** The tags on one visible episode. */
    @GetMapping("/api/plugins/{id}/episodes/{slug}/tags")
    public List<String> tagsOnEpisode(@PathVariable String id, @PathVariable String slug,
                                      Authentication authentication) {
        readable(id, authentication);
        return tags.tagsOn(slug);
    }

    /** The tags on one of this plugin's subjects. */
    @GetMapping("/api/plugins/{id}/subjects/{subjectKey}/tags")
    public List<String> tagsOnSubject(@PathVariable String id, @PathVariable String subjectKey,
                                      Authentication authentication) {
        PluginManifest manifest = readable(id, authentication);
        return tags.tagsOnSubject(manifest.id(), subjectKey);
    }

    /** Tags one of this plugin's own subjects; idempotent, and adds the word to the vocabulary if new. */
    @PutMapping("/api/plugins/{id}/tags/{tag}/subjects/{subjectKey}")
    public ResponseEntity<Void> tagSubject(@PathVariable String id, @PathVariable String tag,
                                           @PathVariable String subjectKey, Authentication authentication) {
        PluginManifest manifest = writable(id, authentication);
        tags.tagSubject(manifest.id(), subjectKey, tag);
        return ResponseEntity.noContent().build();
    }

    /** Removes a tag from one of this plugin's subjects; idempotent. The vocabulary entry stays. */
    @DeleteMapping("/api/plugins/{id}/tags/{tag}/subjects/{subjectKey}")
    public ResponseEntity<Void> untagSubject(@PathVariable String id, @PathVariable String tag,
                                             @PathVariable String subjectKey, Authentication authentication) {
        PluginManifest manifest = writable(id, authentication);
        tags.untagSubject(manifest.id(), subjectKey, tag);
        return ResponseEntity.noContent().build();
    }

    /** Tags an episode as this plugin; needs {@code tags.writesEpisodes}. Additive and idempotent. */
    @PutMapping("/api/plugins/{id}/tags/{tag}/episodes/{slug}")
    public ResponseEntity<Void> tagEpisode(@PathVariable String id, @PathVariable String tag,
                                           @PathVariable String slug, Authentication authentication) {
        PluginManifest manifest = episodeWriter(id, authentication);
        tags.tagEpisode(slug, tag, TagSource.plugin(manifest.id()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes <strong>this plugin's</strong> assignment from an episode; needs {@code tags.writesEpisodes}.
     *
     * <p>Only this plugin's row. If the feed or a podcaster also put the tag there, the episode keeps
     * carrying it — the {@code source} column is what makes that a rule the host enforces rather than one it
     * asks plugins to respect.
     */
    @DeleteMapping("/api/plugins/{id}/tags/{tag}/episodes/{slug}")
    public ResponseEntity<Void> untagEpisode(@PathVariable String id, @PathVariable String tag,
                                             @PathVariable String slug, Authentication authentication) {
        PluginManifest manifest = episodeWriter(id, authentication);
        tags.untagEpisode(slug, tag, TagSource.plugin(manifest.id()));
        return ResponseEntity.noContent().build();
    }

    /**
     * The manifest of a loaded, switched-on plugin that declares a tag surface — 404 otherwise.
     *
     * <p>A disabled plugin is indistinguishable from an absent one, as on every other plugin surface (§7.8):
     * switching a plugin off closes what it can reach immediately, not at the next restart.
     */
    private PluginManifest manifestOf(String id) {
        return plugins.active(id)
                .map(PluginRegistration::manifest)
                .filter(PluginManifest::declaresTags)
                .orElseThrow(() -> new NotFoundException("Unknown plugin, or it declares no tags: " + id));
    }

    private PluginManifest readable(String id, Authentication authentication) {
        PluginManifest manifest = manifestOf(id);
        if (!manifest.readsTagVocabulary()) {
            throw new AccessDeniedException(
                    "Plugin '%s' declared tags.readsVocabulary: false".formatted(manifest.id()));
        }
        if (!PluginAccessPolicy.canRead(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to read plugin data: " + manifest.id());
        }
        return manifest;
    }

    private PluginManifest writable(String id, Authentication authentication) {
        PluginManifest manifest = manifestOf(id);
        if (!PluginAccessPolicy.canWrite(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to write plugin data: " + manifest.id());
        }
        return manifest;
    }

    /**
     * As {@link #writable}, plus the declared capability.
     *
     * <p>Checked after the write floor, for the reason {@code backendOwned} is: a caller with no business in
     * this plugin's data should not learn what its manifest declares.
     */
    private PluginManifest episodeWriter(String id, Authentication authentication) {
        PluginManifest manifest = writable(id, authentication);
        if (!manifest.writesEpisodeTags()) {
            throw new AccessDeniedException(
                    ("Plugin '%s' did not declare tags.writesEpisodes — tagging an episode changes the "
                            + "shell's filters and what core recommends, so it is opt-in")
                            .formatted(manifest.id()));
        }
        return manifest;
    }
}
