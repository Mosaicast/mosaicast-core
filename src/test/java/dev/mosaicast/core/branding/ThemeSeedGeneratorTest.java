// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Risk-point tests for the theme seed generator (ARCHITECTURE §12.3, §13.5): whatever accent the admin
 * picks — even a near-white or fully-saturated one — the generated text always clears WCAG AA against its
 * background, in both light and dark modes. This is the "no unreadable theme can result" guarantee.
 */
class ThemeSeedGeneratorTest {

    private final ThemeSeedGenerator generator = new ThemeSeedGenerator();

    @ParameterizedTest
    @ValueSource(strings = {
        "#C8553D", // the Mosaicast brand accent
        "#FFFF00", // bright yellow (very light — worst case for dark text on light bg)
        "#FFFFFF", // white accent (pathological)
        "#000080", // dark navy
        "#00FF00", // saturated green
        "#111111", // near-black accent
        "#FFF176", // the pale yellow that measured 1.12:1 as link text (core#162)
    })
    void generatedTextAlwaysMeetsWcagAa(String accent) {
        GeneratedTheme theme = generator.generate(accent);

        assertTextReadable(theme.light());
        assertTextReadable(theme.dark());
    }

    private static void assertTextReadable(ThemeTokenSet tokens) {
        assertThat(Oklch.contrast(tokens.text(), tokens.bg()))
                .as("text vs bg")
                .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
        assertThat(Oklch.contrast(tokens.textMuted(), tokens.bg()))
                .as("muted text vs bg")
                .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
        // The accent as a foreground — links, the active tab, the focus ring — on both colours it sits on.
        assertThat(Oklch.contrast(tokens.accentText(), tokens.bg()))
                .as("accent text vs bg")
                .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
        assertThat(Oklch.contrast(tokens.accentText(), tokens.surface()))
                .as("accent text vs surface")
                .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
    }

    @Test
    void theRawAccentKeepsItsMeaningAndAReadableAccentIsLeftAlone() {
        // `--mc-accent` is a plugin contract: it stays the seed. And the clamp moves a colour only as far as
        // it must — the brand accent already reads on the light page, so its text form is itself.
        GeneratedTheme theme = generator.generate("#1a5fb4");

        assertThat(theme.light().accent()).isEqualTo("#1a5fb4");
        assertThat(theme.light().accentText()).isEqualTo("#1a5fb4");
        GeneratedTheme pale = generator.generate("#FFF176");
        assertThat(pale.light().accent()).isEqualToIgnoringCase("#fff176");
        assertThat(pale.light().accentText()).isNotEqualToIgnoringCase("#fff176");
    }

    @Test
    void roundTripsAKnownColourThroughOklch() {
        // sanity: conversion is stable enough that a hex survives the round trip within rounding.
        assertThat(Oklch.oklchToHex(Oklch.hexToOklch("#c8553d"))).isEqualTo("#c8553d");
    }

    // ---- core#281: accent2 is used as text and as a chip fill, so it carries the same guarantees ----

    /**
     * A grid of accents — hue in 15° steps at several lightness/chroma levels, plus the extremes. The
     * existing cases all passed while accent2 was unreadable for half of the usual brand colours, because
     * none of them had a light accent in light mode or a dark one in dark mode.
     */
    static java.util.stream.Stream<String> accentGrid() {
        java.util.List<String> accents = new java.util.ArrayList<>(
                java.util.List.of("#000000", "#111111", "#ffffff", "#ffff00", "#c8981c", "#cc9720"));
        for (double l : new double[] {0.30, 0.55, 0.75, 0.92}) {
            for (double c : new double[] {0.05, 0.12, 0.20}) {
                for (int h = 0; h < 360; h += 15) {
                    accents.add(Oklch.oklchToHex(new Oklch.Color(l, c, h)));
                }
            }
        }
        return accents.stream();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("accentGrid")
    void theSecondAccentReadsAsTextAndCarriesReadableChipText(String accent) {
        GeneratedTheme theme = generator.generate(accent);
        for (ThemeTokenSet tokens : java.util.List.of(theme.light(), theme.dark())) {
            assertTextReadable(tokens);
            assertThat(Oklch.contrast(tokens.accent2(), tokens.bg()))
                    .as("accent2 vs bg for %s", accent)
                    .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
            assertThat(Oklch.contrast(tokens.accent2(), tokens.surface()))
                    .as("accent2 vs surface for %s", accent)
                    .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
            assertThat(Oklch.contrast(tokens.accent2Contrast(), tokens.accent2()))
                    .as("chip text on accent2 for %s", accent)
                    .isGreaterThanOrEqualTo(ThemeSeedGenerator.MIN_TEXT_CONTRAST);
        }
    }

    @Test
    void aGoldAccentKeepsAWarmSecondAccent() {
        // Game of Pods: +40° turned its gold into olive green. Warm accents rotate the other way.
        double goldHue = Oklch.hexToOklch("#c8981c").h();
        double secondHue = ThemeSeedGenerator.secondaryAccent(Oklch.hexToOklch("#c8981c")).h();

        assertThat(goldHue).isBetween(ThemeSeedGenerator.WARM_FROM, ThemeSeedGenerator.WARM_TO);
        assertThat(secondHue).isCloseTo(goldHue - 40.0, org.assertj.core.data.Offset.offset(0.001));
        // and the light-mode value really is no longer a green (OKLCH greens sit around 120–150°)
        assertThat(Oklch.hexToOklch(generator.generate("#c8981c").light().accent2()).h()).isLessThan(WARM_GREEN);
    }

    private static final double WARM_GREEN = 100.0;

    @Test
    void aCoolAccentStillRotatesForwards() {
        Oklch.Color blue = Oklch.hexToOklch("#1a5fb4");
        assertThat(ThemeSeedGenerator.secondaryAccent(blue).h())
                .isCloseTo((blue.h() + 40.0) % 360.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
