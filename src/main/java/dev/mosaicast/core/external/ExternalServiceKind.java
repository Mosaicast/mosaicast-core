// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import java.util.Locale;
import java.util.Optional;

/**
 * A category of third-party service an instance may use (ARCHITECTURE §12.7).
 *
 * <p>One kind, one admin section, one selected provider (or none). Translation is the first; transcription,
 * text-to-speech and embeddings are the shapes this was built to take next.
 *
 * <p><strong>An enum rather than a registry of strings</strong>, because the value is three things at once: a
 * database column, a URL segment and an i18n key suffix. All three want it closed and stable. The generics a
 * kind needs live on {@link ExternalKindSupport}, so this never has to carry type parameters.
 */
public enum ExternalServiceKind {

    /** Machine translation (§12.7). */
    TRANSLATION;

    /** The stable lower-case id: the REST path segment, the stored value, and the i18n key suffix. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The kind a path segment or stored value names.
     *
     * @param id a kind id, case-insensitive
     * @return the kind, or empty if nothing answers to that id
     */
    public static Optional<ExternalServiceKind> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (ExternalServiceKind kind : values()) {
            if (kind.id().equals(wanted)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
