// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import dev.mosaicast.core.external.ExternalProvider;

/** A translation service implementation (ARCHITECTURE §12.7). */
public interface TranslationProvider extends ExternalProvider<TranslationRequest, TranslationResult> {

    /**
     * How many units this input consumes — characters, for every translation provider so far.
     *
     * <p>Code points rather than {@code length()}: a provider billing per character does not charge twice
     * for one emoji, and an estimate that did would drift on exactly the text most likely to contain them.
     */
    @Override
    default long estimateUnits(TranslationRequest input) {
        return input.text().codePointCount(0, input.text().length());
    }
}
