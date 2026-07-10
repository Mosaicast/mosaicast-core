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
    }

    @Test
    void roundTripsAKnownColourThroughOklch() {
        // sanity: conversion is stable enough that a hex survives the round trip within rounding.
        assertThat(Oklch.oklchToHex(Oklch.hexToOklch("#c8553d"))).isEqualTo("#c8553d");
    }
}
