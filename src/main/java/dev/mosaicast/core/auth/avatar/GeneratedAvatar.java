// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The avatar everybody starts with (ARCHITECTURE §8.7): an initial over a colour derived from the user id.
 *
 * <p>One mechanism covers every case that would otherwise need its own answer — a provider with no avatars
 * at all, a provider avatar that is simply absent, an account that has re-anonymised, and a user who has
 * been deleted (§12.8). It costs no storage, no outbound request and no CSP widening.
 *
 * <p><strong>Why the palette is fixed rather than themed.</strong> The shell draws its own version of this
 * from {@code --mc-*} tokens, so it re-themes with the site (§12.3). This one is served as bytes to
 * anything that asks — including a plugin rendering a leaderboard (§8.8) — and the server does not know the
 * viewer's theme. The duplication is deliberate: the alternative is answering 404 for users with no
 * provider picture, which would make every plugin reimplement the fallback and get it slightly different.
 * The colours are therefore chosen to hold their contrast against white text in both light and dark
 * surroundings.
 */
public final class GeneratedAvatar {

    /** Mid-tone hues that keep 4.5:1 against the white glyph and read as deliberate on either background. */
    private static final String[] PALETTE = {
        "#7d5ba6", "#2f6f8f", "#b5651d", "#3f7d58",
        "#a03e52", "#4a5d94", "#8a6d3b", "#5d6b7a",
    };

    private GeneratedAvatar() {
    }

    /** The media type {@link #svgFor} produces. */
    public static final String MIME = "image/svg+xml";

    /**
     * An SVG avatar for a user.
     *
     * @param userId      the user's id — the colour is derived from it, so it survives every rename
     * @param displayName the name to take an initial from; may be null or empty
     * @return the SVG document, UTF-8 encoded
     */
    public static byte[] svgFor(UUID userId, String displayName) {
        String colour = PALETTE[Math.floorMod(userId.hashCode(), PALETTE.length)];
        String initial = initialOf(displayName);
        // No external font: a name is set in whatever the viewer has, because an avatar that waits on a
        // webfont is an avatar that renders blank on a strict CSP (§12.5) and on the plugin surface.
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">\
                <rect width="64" height="64" rx="32" fill="%s"/>\
                <text x="32" y="33" fill="#ffffff" font-family="system-ui,sans-serif" font-size="30" \
                font-weight="600" text-anchor="middle" dominant-baseline="central">%s</text></svg>"""
                .formatted(colour, escape(initial));
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A stable identity for the generated image, so the browser can revalidate rather than refetch.
     *
     * <p>Covers the name as well as the id: the glyph changes when someone renames, and an ETag that did
     * not notice would leave the old initial in every cache that had seen it.
     */
    public static String etagFor(UUID userId, String displayName) {
        return "\"gen-" + Integer.toHexString(userId.hashCode()) + "-"
                + Integer.toHexString(initialOf(displayName).hashCode()) + "\"";
    }

    /**
     * The first character of a name, uppercased.
     *
     * <p>Taken by codepoint rather than by {@code charAt}, or a name beginning with an emoji or any other
     * astral character yields half a surrogate pair — which is not a character and does not render.
     */
    private static String initialOf(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String trimmed = displayName.strip();
        return new String(Character.toChars(trimmed.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
    }

    /** XML-escapes the glyph — a display name is user input and this one is going into a document. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
