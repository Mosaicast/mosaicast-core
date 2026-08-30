// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import java.util.Locale;
import java.util.Objects;

/**
 * The generalized translation input (ARCHITECTURE §12.7).
 *
 * <p>Provider-independent on purpose: LibreTranslate's {@code {q, source, target, format}}, Google's
 * {@code {q[], source, target}} and Microsoft's {@code {Text:[…]}} all reduce to this, so a consumer keeps
 * working when the operator switches provider.
 *
 * @param format markdown is <em>neither</em> option. Sent as text it comes back with mangled links and code
 *               fences, because a translator does not know they are markup; splitting markdown into
 *               translatable blocks is the caller's job, and a real one
 */
public record TranslationRequest(String text, String from, String to, Format format) {

    /** Let the provider detect the source language. */
    public static final String AUTO = "auto";

    /** A ceiling the host imposes regardless of what a provider would accept. */
    public static final int MAX_CHARS = 50_000;

    public enum Format {
        TEXT,
        HTML
    }

    public TranslationRequest {
        Objects.requireNonNull(text, "text");
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("A translation needs a target language");
        }
        if (text.length() > MAX_CHARS) {
            // Refused rather than truncated: half a translated paragraph reads as a complete one.
            throw new IllegalArgumentException(
                    "Text is longer than the %d character limit".formatted(MAX_CHARS));
        }
        from = from == null || from.isBlank() ? AUTO : from.trim().toLowerCase(Locale.ROOT);
        to = to.trim().toLowerCase(Locale.ROOT);
        format = format == null ? Format.TEXT : format;
    }

    /** Plain text, source detected by the provider. */
    public static TranslationRequest of(String text, String to) {
        return new TranslationRequest(text, AUTO, to, Format.TEXT);
    }

    /** Plain text, source stated — cheaper and more accurate than detection when it is known. */
    public static TranslationRequest of(String text, String from, String to) {
        return new TranslationRequest(text, from, to, Format.TEXT);
    }

    /** Whether the source language is to be detected. */
    public boolean detects() {
        return AUTO.equals(from);
    }
}
