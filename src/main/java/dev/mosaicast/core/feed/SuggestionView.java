// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.UUID;

/**
 * A fuzzy PLANNED-binding suggestion as shown to the podcaster (ARCHITECTURE §5.3): which planned episode
 * might correspond to which feed item, and how strong the title match is. Confirming it binds the two.
 *
 * @param id           the suggestion's id (used to confirm/dismiss)
 * @param plannedRefId the PLANNED episode ref the suggestion would bind
 * @param plannedTitle its provisional title
 * @param rawTitle     the feed item's title
 * @param similarity   the normalized title similarity in {@code [0,1]}
 */
public record SuggestionView(
        UUID id, UUID plannedRefId, String plannedTitle, String rawTitle, double similarity) {

    static SuggestionView of(BindingSuggestion s) {
        return new SuggestionView(
                s.getId(), s.getPlannedRefId(), s.getPlannedTitle(), s.getRawTitle(), s.getSimilarity());
    }
}
