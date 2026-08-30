// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.plugin.PluginManifest.Frontend;
import dev.mosaicast.core.plugin.PluginManifest.Slot;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public plugin manifest the shell fetches to mount plugin Web Components (ARCHITECTURE §7.3/§7.5). Only
 * the client-facing fields of loaded <em>and activated</em> plugins are exposed — frontend bundle and slots —
 * never backend or source details. Switching a plugin off drops it from this list, so the shell unmounts its
 * elements on the next fetch without waiting for a restart (§7.8).
 */
@RestController
public class PluginManifestController {

    private final PluginLoaderService plugins;
    private final dev.mosaicast.core.external.translation.TranslationService translations;

    public PluginManifestController(PluginLoaderService plugins,
                                    dev.mosaicast.core.external.translation.TranslationService translations) {
        this.plugins = plugins;
        this.translations = translations;
    }

    @GetMapping("/api/plugins/manifest")
    public List<PublicPlugin> manifest() {
        return plugins.allActive().stream()
                .map(PluginRegistration::manifest)
                .map(m -> new PublicPlugin(m.id(), m.name(), m.version(), m.frontend(), m.slots(),
                        !m.schemaEntities().isEmpty(), m.declaresBlobs(), m.declaresTags(),
                        m.writesEpisodeTags(), hasTranslation(m),
                        m.license(), m.author(), m.homepage(), m.attribution()))
                .toList();
    }

    /**
     * Whether the shell should build a {@code ctx.translation} client for this plugin: the manifest declared
     * the kind <strong>and</strong> the operator has a provider configured (§16).
     *
     * <p>One flag for two conditions on purpose. The SDK makes the two reasons for {@code null}
     * <em>deliberately indistinguishable</em> — whether you declared is a static fact about a file you wrote,
     * so a runtime discriminator would be API surface for a question {@code plugin.json} already answers —
     * and collapsing them here is that contract rather than a shortcut.
     *
     * <p>The declaration half is checked first, so this reveals nothing about the instance's provider to a
     * plugin that never asked.
     */
    private boolean hasTranslation(PluginManifest manifest) {
        return manifest.usesExternalKind(dev.mosaicast.core.external.ExternalServiceKind.TRANSLATION)
                && translations.available();
    }

    /**
     * The client-facing subset of a plugin manifest.
     *
     * @param hasSchema whether the plugin declares {@code storage.schema}, which is what tells the shell to
     *                  build a {@code ctx.schema} client for it rather than handing it {@code null} — the
     *                  frontend mirror of the Java {@code ctx.schema()} being {@code null} for a doc-store
     *                  plugin. The entity names themselves stay out: the host resolves those per request,
     *                  and a plugin already knows what it declared.
     * @param hasBlobs  whether the plugin declares a {@code blobs} block, the same signal for
     *                  {@code ctx.blobs} (§11). The declared limits stay out: they are the manifest's
     *                  *ask*, this install may grant less, and the only honest source is the quota
     *                  endpoint.
     * @param hasTags   whether the plugin declares a {@code tags} block, the same signal again for
     *                  {@code ctx.tags} (§6.1)
     * @param tagsWriteEpisodes whether it declared the episode-tagging capability. Public because it is one:
     *                  what a plugin may change about the site's own filters and recommendations is not a
     *                  secret from the visitor looking at the result, and the shell uses it to decide whether
     *                  to offer the write at all rather than to let it fail at the endpoint
     * @param hasTranslation whether the shell should hand this plugin a {@code ctx.translation} client (§16).
     *                  <strong>Unlike the three flags above it is not pure declaration</strong>: it is the
     *                  declaration <em>and</em> a configured provider, because the SDK makes those two
     *                  reasons for {@code null} indistinguishable by design. It therefore goes stale the way
     *                  plugin activation does — an admin removing the provider is picked up on the shell's
     *                  next manifest fetch, which is why the SDK tells authors not to cache the handle. It is
     *                  <em>not</em> gated on the caller's role: a visitor below {@code external.usedBy} holds
     *                  a working-looking client whose {@code translate()} is a 403, which is the contract
     * @param license   SPDX identifier the plugin declared, or {@code null}. Anonymous by design: what a
     *                  visitor is owed on the About page is what this install runs and under what terms,
     *                  and that is not privileged information.
     * @param author    who the plugin credits as its author, or {@code null}
     * @param homepage  the plugin's own page, or {@code null}
     * @param attribution whoever else the plugin wants to credit, or {@code null}
     */
    public record PublicPlugin(String id, String name, String version, Frontend frontend, List<Slot> slots,
                               boolean hasSchema, boolean hasBlobs, boolean hasTags,
                               boolean tagsWriteEpisodes, boolean hasTranslation,
                               String license, String author, String homepage, String attribution) {
    }
}
