// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.external.error.ExternalBusyException;
import dev.mosaicast.core.external.error.ExternalRateLimitedException;
import dev.mosaicast.core.external.error.ExternalServiceException;
import dev.mosaicast.core.external.error.ExternalTimeoutException;
import dev.mosaicast.core.external.error.NoProviderConfiguredException;
import dev.mosaicast.core.external.error.ProviderMisconfiguredException;
import dev.mosaicast.core.external.translation.TranslationService;
import dev.mosaicast.plugin.api.Translation;
import dev.mosaicast.plugin.api.TranslationException;
import dev.mosaicast.plugin.api.TranslationRequest;
import dev.mosaicast.plugin.api.TranslationResult;

/**
 * The backend {@code ctx.translation} of a plugin that declared {@code external.kinds: ["translation"]}
 * (ARCHITECTURE §16, SDK 0.11.0) — the same shape {@link TagsImpl} and {@link PluginBlobsImpl} have: built
 * only for a plugin whose manifest asked, and never handed to one that did not.
 *
 * <p>Everything cross-cutting already happened by the time this is reached: {@link TranslationService} goes
 * through the call pipeline, so the cache, the rate limit and the concurrency bound are properties of the
 * system rather than of this class remembering them.
 *
 * <p><strong>No role floor here.</strong> {@code external.usedBy} governs the browser endpoint only: a
 * backend call happens inside {@code register} or on a timer and has nobody to have a role. The floor lives
 * in {@link PluginExternalController}.
 *
 * <p>The one real job is translating core's failure vocabulary into the SDK's, so a plugin can retry a
 * {@link TranslationException.Reason#BUSY} and give up on a {@link TranslationException.Reason#NO_PROVIDER}
 * without matching on English. Only the message travels — core never puts an upstream response body in one
 * of these, because an error body can echo the credential that was sent with the request.
 */
class PluginTranslationImpl implements Translation {

    private final TranslationService translations;

    PluginTranslationImpl(TranslationService translations) {
        this.translations = translations;
    }

    @Override
    public TranslationResult translate(TranslationRequest request) throws TranslationException {
        try {
            dev.mosaicast.core.external.translation.TranslationResult result =
                    translations.translate(toCore(request));
            return new TranslationResult(result.text(), result.detectedSourceLanguage(), result.providerId(),
                    result.fromCache());
        } catch (ExternalServiceException ex) {
            throw new TranslationException(reasonOf(ex), ex.getMessage(), ex);
        }
    }

    @Override
    public boolean available() {
        return translations.available();
    }

    /**
     * The SDK's request as core's.
     *
     * <p>Two records that look alike and are not the same contract: core's carries the host's
     * {@code MAX_CHARS} ceiling, and refuses past it with an {@link IllegalArgumentException} the SDK
     * documents on this call. Mapping by hand rather than sharing the type is what lets the host tighten
     * that ceiling without a {@code platformApi} bump.
     */
    private static dev.mosaicast.core.external.translation.TranslationRequest toCore(
            TranslationRequest request) {
        return new dev.mosaicast.core.external.translation.TranslationRequest(request.text(), request.from(),
                request.to(),
                request.format() == TranslationRequest.Format.HTML
                        ? dev.mosaicast.core.external.translation.TranslationRequest.Format.HTML
                        : dev.mosaicast.core.external.translation.TranslationRequest.Format.TEXT);
    }

    private static TranslationException.Reason reasonOf(ExternalServiceException ex) {
        return switch (ex) {
            case NoProviderConfiguredException ignored -> TranslationException.Reason.NO_PROVIDER;
            case ProviderMisconfiguredException ignored -> TranslationException.Reason.MISCONFIGURED;
            case ExternalRateLimitedException ignored -> TranslationException.Reason.RATE_LIMITED;
            case ExternalBusyException ignored -> TranslationException.Reason.BUSY;
            case ExternalTimeoutException ignored -> TranslationException.Reason.TIMEOUT;
            // Including ExternalProviderException, and anything a later kind adds: a failure the plugin
            // cannot name is still a failure of the provider from where it stands.
            default -> TranslationException.Reason.PROVIDER_FAILED;
        };
    }
}
