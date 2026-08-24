// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

/**
 * One option in the shell's tag filter (§6.1): the canonical key the URL and the API use, and the label a
 * visitor reads.
 *
 * <p>Two fields rather than one because the host normalises keys — {@code Maritime} and {@code maritime }
 * converge — and a visitor should not see a filter list lower-cased by an internal rule they never asked
 * about.
 *
 * @param tag   the canonical key; what {@code ?tag=} carries and what plugins send and receive
 * @param label the display spelling, kept from the tag's first use
 */
public record TagOption(String tag, String label) {
}
