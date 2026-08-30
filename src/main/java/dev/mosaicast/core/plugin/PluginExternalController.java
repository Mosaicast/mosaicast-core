// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.translation.TranslationRequest;
import dev.mosaicast.core.external.translation.TranslationResult;
import dev.mosaicast.core.external.translation.TranslationService;
import dev.mosaicast.core.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-plugin HTTP surface for the instance's external services (ARCHITECTURE §16) — the endpoints behind
 * the browser's {@code ctx.translation}, mirroring the Java {@link dev.mosaicast.plugin.api.Translation}.
 *
 * <p><strong>Two gates, and their order is the design.</strong>
 *
 * <ol>
 *   <li>A plugin whose manifest does not declare the kind has no surface at all and gets a <strong>404</strong>,
 *       indistinguishable from an unknown plugin — the answer the blob, schema and tag surfaces give. It is
 *       checked <em>first</em>, before anything asks whether a provider is selected, so an undeclared caller
 *       cannot read off the error code whether this instance pays for translation. Deliberately not
 *       {@code external-no-provider}, which would send an author to their admin about something no admin can
 *       grant.</li>
 *   <li>Past that, {@code external.usedBy} — <strong>403</strong> below the floor. A non-null handle is not
 *       permission: the shell hands the client to every visitor the manifest declared the kind for, because
 *       the alternative is a role check duplicated in the browser, where it decides nothing.</li>
 * </ol>
 *
 * <p>This is the half of the surface that spends money: a {@code translate()} in a page means anyone who can
 * load that page can bill a metered API. That is why the floor exists here and not on the backend handle,
 * where {@code register} and {@code onSchedule} have no caller to have a role.
 *
 * <p>Everything past the gates is {@link TranslationService}: the cache, the rate limit and the concurrency
 * bound are the pipeline's, and its failure vocabulary already carries its own statuses through
 * {@code ApiExceptionHandler} — 409 for no provider or a misconfigured one, 429, 503, 504, 502.
 */
@RestController
public class PluginExternalController {

    private static final Logger log = LoggerFactory.getLogger(PluginExternalController.class);

    private final PluginLoaderService plugins;
    private final TranslationService translations;

    public PluginExternalController(PluginLoaderService plugins, TranslationService translations) {
        this.plugins = plugins;
        this.translations = translations;
    }

    /**
     * Translates one string on behalf of a plugin's UI.
     *
     * @param request what to translate, and into what — the wire form of the SDK's {@code TranslationRequest}
     */
    @PostMapping("/api/plugins/{id}/external/translation")
    public TranslationView translate(@PathVariable String id, @RequestBody TranslateRequest request,
                                     Authentication authentication) {
        PluginManifest manifest = caller(id, ExternalServiceKind.TRANSLATION, authentication);
        // The pipeline's rate limit keys on kind and provider, so without this nothing records which plugin
        // spent the site's budget (§16). One line per call, at info, because that is the question an operator
        // asks after a bill and there is no other place it is answered.
        log.info("Plugin '{}' is translating {} characters into '{}'", manifest.id(),
                request.text() == null ? 0 : request.text().length(), request.to());
        TranslationResult result = translations.translate(new TranslationRequest(request.text(),
                request.from(), request.to(), formatOf(request.format())));
        return new TranslationView(result.text(), result.detectedSourceLanguage(), result.providerId(),
                result.fromCache());
    }

    /**
     * The manifest of a loaded, switched-on plugin that declared this kind and whose caller meets its floor.
     *
     * <p>A disabled plugin is indistinguishable from an absent one, as on every other plugin surface (§7.8):
     * switching a plugin off closes what it can reach immediately, not at the next restart.
     */
    private PluginManifest caller(String id, ExternalServiceKind kind, Authentication authentication) {
        PluginManifest manifest = plugins.active(id)
                .map(PluginRegistration::manifest)
                .filter(m -> m.usesExternalKind(kind))
                .orElseThrow(() -> new NotFoundException(
                        "Unknown plugin, or it declares no external %s: %s".formatted(kind.id(), id)));
        if (!PluginAccessPolicy.meetsFloor(manifest.externalUsedBy(), CurrentUser.role(authentication))) {
            throw new AccessDeniedException(("Plugin '%s' declared external.usedBy: %s — an external call "
                    + "spends this site's budget, so it takes at least that role")
                    .formatted(manifest.id(), manifest.externalUsedBy()));
        }
        return manifest;
    }

    /** Anything but an explicit {@code HTML} is text — the same default the SDK's own record applies. */
    private static TranslationRequest.Format formatOf(String format) {
        return "html".equalsIgnoreCase(format)
                ? TranslationRequest.Format.HTML : TranslationRequest.Format.TEXT;
    }

    /**
     * The wire form of a translation request.
     *
     * @param from a locale code, or {@code auto} / absent to let the provider detect it
     * @param to   a locale code; required, and a blank one is a 400 from the record's own check
     */
    public record TranslateRequest(String text, String from, String to, String format) {
    }

    /** The wire form of a translation, one-to-one with the SDK's {@code TranslationResult}. */
    public record TranslationView(String text, String detectedSourceLanguage, String providerId,
                                  boolean fromCache) {
    }
}
