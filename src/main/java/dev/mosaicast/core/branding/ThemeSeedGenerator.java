// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import dev.mosaicast.core.branding.Oklch.Color;
import org.springframework.stereotype.Component;

/**
 * Generates a full, readable theme from one accent (ARCHITECTURE §12.3): the admin sets a single accent,
 * the system derives the rest in OKLCH (perceptually even) and <strong>clamps text against its background
 * to WCAG AA (≥ 4.5:1)</strong>, so no accent can produce an unreadable theme. Neutrals are tinted with a
 * trace of the accent hue so light and dark feel of a piece.
 *
 * <p>What is guaranteed, in both modes: {@code text}, {@code textMuted}, {@code accentText} and
 * {@code accent2} reach 4.5:1 on {@code bg} and {@code surface} (the last two are used as text by the shell
 * and by plugins through {@code ctx.theme}); {@code accentContrast} is the more readable of white and
 * near-black on {@code accent}, and {@code accent2Contrast} reaches 4.5:1 on {@code accent2} (core#281).
 */
@Component
public class ThemeSeedGenerator {

    /** WCAG AA contrast for normal text. */
    public static final double MIN_TEXT_CONTRAST = 4.5;

    private static final double NEUTRAL_CHROMA = 0.012;
    private static final int SEARCH_ITERATIONS = 28;

    /** Builds the light + dark token sets from an accent {@code #rrggbb}. */
    public GeneratedTheme generate(String accentHex) {
        Color accent = Oklch.hexToOklch(accentHex);
        double hue = accent.h();
        return new GeneratedTheme(accentHex, light(accent, hue), dark(accent, hue));
    }

    private ThemeTokenSet light(Color accent, double hue) {
        String bg = neutral(0.985, hue);
        String surface = neutral(0.998, hue);
        String border = neutral(0.90, hue);
        String text = clampText(new Color(0.22, 0.02, hue), bg, true);
        String textMuted = clampText(new Color(0.50, 0.02, hue), bg, true);
        String accentHex = Oklch.oklchToHex(accent);
        String accent2 = accentText(secondaryAccent(accent), bg, surface, true);
        return new ThemeTokenSet(bg, surface, text, textMuted,
                accentHex, accentContrast(accentHex), border, accent2,
                accentText(accent, bg, surface, true), accentContrast(accent2));
    }

    private ThemeTokenSet dark(Color accent, double hue) {
        String bg = neutral(0.17, hue);
        String surface = neutral(0.22, hue);
        String border = neutral(0.32, hue);
        String text = clampText(new Color(0.95, 0.015, hue), bg, false);
        String textMuted = clampText(new Color(0.72, 0.015, hue), bg, false);
        // On a dark page the accent needs enough lightness to read as a colour, not a smudge.
        Color darkAccent = new Color(Math.max(accent.l(), 0.62), accent.c(), hue);
        String accentHex = Oklch.oklchToHex(darkAccent);
        // From the lifted accent, like every other dark token: from the raw one, a dark seed gave an accent2
        // that vanished on the dark page (#111111 measured 1.09:1, core#281).
        String accent2 = accentText(secondaryAccent(darkAccent), bg, surface, false);
        return new ThemeTokenSet(bg, surface, text, textMuted,
                accentHex, accentContrast(accentHex), border, accent2,
                accentText(darkAccent, bg, surface, false), accentContrast(accent2));
    }

    /**
     * The accent, moved only as far as it must be to read as text on both page colours (core#162).
     *
     * <p>The raw seed was used for link text, the active tab and — through {@code --mc-focus} — the keyboard
     * focus ring, while only {@code text} and {@code textMuted} were clamped. A pale seed ({@code #FFF176})
     * measured 1.12:1 on the light background: links and the active nav invisible, and a focus ring a theme
     * could erase. Clamped against both colours a foreground sits on; the lightness search is monotonic, so
     * the second clamp only continues the first.
     */
    private static String accentText(Color accent, String bgHex, String surfaceHex, boolean lightTheme) {
        String againstBg = clampText(accent, bgHex, lightTheme);
        return clampText(Oklch.hexToOklch(againstBg), surfaceHex, lightTheme);
    }

    private static String neutral(double lightness, double hue) {
        return Oklch.oklchToHex(new Color(lightness, NEUTRAL_CHROMA, hue));
    }

    /** OKLCH hues of the warm band (orange through yellow and gold), where +40° would land in green. */
    static final double WARM_FROM = 35.0;
    static final double WARM_TO = 115.0;

    /**
     * A harmonious second accent: the hue rotated by 40°, lightness and chroma kept, before the text clamp.
     *
     * <p>Warm accents rotate the other way. +40° turns any gold, yellow or orange into green — technically
     * harmonious, and wrong for a brand built on gold (Game of Pods' {@code #c8981c} became olive). −40°
     * keeps them warm: gold becomes bronze and rust, orange becomes red (core#281).
     */
    static Color secondaryAccent(Color accent) {
        double hue = accent.h();
        boolean warm = hue >= WARM_FROM && hue < WARM_TO;
        double rotated = warm ? hue - 40.0 : hue + 40.0;
        return new Color(accent.l(), accent.c(), ((rotated % 360.0) + 360.0) % 360.0);
    }

    /**
     * Clamps a text colour's OKLCH lightness against {@code bgHex} until it meets WCAG AA, moving toward
     * darker ({@code lightTheme}) or lighter ({@code !lightTheme}) only as far as needed.
     */
    private static String clampText(Color start, String bgHex, boolean lightTheme) {
        if (Oklch.contrast(Oklch.oklchToHex(start), bgHex) >= MIN_TEXT_CONTRAST) {
            return Oklch.oklchToHex(start);
        }
        // Contrast is monotonic in lightness: for a light background it grows as text darkens, and vice
        // versa. Binary-search the lightness closest to the original that still meets the threshold.
        double lo = lightTheme ? 0.0 : start.l();
        double hi = lightTheme ? start.l() : 1.0;
        for (int i = 0; i < SEARCH_ITERATIONS; i++) {
            double mid = (lo + hi) / 2.0;
            boolean ok = Oklch.contrast(
                    Oklch.oklchToHex(new Color(mid, start.c(), start.h())), bgHex) >= MIN_TEXT_CONTRAST;
            if (lightTheme) {
                if (ok) {
                    lo = mid; // can afford to be lighter (closer to original)
                } else {
                    hi = mid;
                }
            } else {
                if (ok) {
                    hi = mid; // can afford to be darker (closer to original)
                } else {
                    lo = mid;
                }
            }
        }
        double chosen = lightTheme ? lo : hi;
        return Oklch.oklchToHex(new Color(chosen, start.c(), start.h()));
    }

    /** The more readable of white/near-black text on the accent (used for buttons etc.). */
    private static String accentContrast(String accentHex) {
        String light = "#ffffff";
        String dark = "#111111";
        return Oklch.contrast(accentHex, light) >= Oklch.contrast(accentHex, dark) ? light : dark;
    }
}
