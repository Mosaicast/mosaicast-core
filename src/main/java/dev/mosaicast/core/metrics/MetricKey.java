// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import java.time.Instant;

/**
 * One counter: an hour bucket, who counted it, what, and about which subject (ARCHITECTURE §19). Nothing
 * in it describes a visitor.
 *
 * @param hour      the start of the UTC hour
 * @param source    {@code core} or {@code edge}
 * @param kind      {@code view}, {@code play} or {@code progress}
 * @param subject   e.g. {@code episode/the-cast-s01e06}, {@code home}, {@code edge/countdown}
 * @param milestone 25/50/75/100 for {@code progress}, else 0
 */
public record MetricKey(Instant hour, String source, String kind, String subject, int milestone) {
}
