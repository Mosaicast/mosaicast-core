// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ExternalServices;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.error.ExternalServiceException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What core — and later plugins — call to translate something (ARCHITECTURE §12.7).
 *
 * <p>Thin by design. It exists so a consumer names a translation concept rather than the generic gateway,
 * and so there is one stable façade to put behind the SDK later. Everything cross-cutting (caching, the
 * concurrency bound, rate limiting) wraps the provider underneath and no caller changes when it lands.
 *
 * <p><strong>Synchronous, deliberately.</strong> Both consumers — an admin who pressed a button, and a
 * plugin's scheduled job — want an answer. A job table and polling is machinery nobody needs for a call
 * that takes about a second. {@link ExternalServices#require} is the single seam: when a kind arrives whose
 * calls take minutes, a job service goes behind it and no consumer of this class is affected. Do not
 * helpfully make everything async before then.
 *
 * <p><strong>Machine output is a draft.</strong> Callers store it marked as one and let a human confirm it,
 * the way the legal-page prefill hands the admin something unsaved. A translation nobody has read is not
 * made safer by being automatic.
 */
@Service
public class TranslationService {

    private final ExternalServices services;

    public TranslationService(ExternalServices services) {
        this.services = services;
    }

    /**
     * Translates one string.
     *
     * @throws ExternalServiceException when the host refuses or the provider fails — no provider selected,
     *                                  a missing setting, a timeout, an upstream error. Each carries its own
     *                                  HTTP status and stable problem type; let it surface rather than
     *                                  falling back to the untranslated string, which a reader cannot tell
     *                                  apart from a real translation
     */
    @SuppressWarnings("unchecked")
    public TranslationResult translate(TranslationRequest request) {
        ExternalServices.Resolved resolved = services.require(ExternalServiceKind.TRANSLATION);
        TranslationProvider provider = (TranslationProvider) resolved.provider();
        return provider.call(request, resolved.config());
    }

    /**
     * Whether a call would even be attempted.
     *
     * <p>Advisory: an admin can remove the provider between this and the next {@link #translate}. Use it to
     * disable a button, and still handle the exception.
     */
    public boolean available() {
        return services.ready(ExternalServiceKind.TRANSLATION);
    }

    /** Which provider is selected, if any — worth storing next to anything kept. */
    public Optional<ProviderDescriptor> activeProvider() {
        return services.resolve(ExternalServiceKind.TRANSLATION).map(ExternalServices.Resolved::descriptor);
    }
}
