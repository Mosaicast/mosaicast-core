// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

/**
 * What came back from a translation (ARCHITECTURE §12.7).
 *
 * @param detectedSourceLanguage what the provider thinks the source was, or {@code null} when it does not
 *                               say — which is the ordinary case when the caller stated the source
 * @param providerId             which provider produced it; the admin can change that, so it is worth
 *                               storing beside anything kept
 * @param fromCache              set by the host, never by a provider
 */
public record TranslationResult(
        String text, String detectedSourceLanguage, String providerId, boolean fromCache) {

    /** The same result, marked as having come from the cache. */
    public TranslationResult cached() {
        return new TranslationResult(text, detectedSourceLanguage, providerId, true);
    }
}
