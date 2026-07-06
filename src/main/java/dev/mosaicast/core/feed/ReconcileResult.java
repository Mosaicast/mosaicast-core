// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.UUID;

/**
 * Summary of one reconciliation run (ARCHITECTURE §5.2/§5.3): how many refs were created, refreshed,
 * withdrawn or PLANNED-bound, plus any fuzzy-title {@link Suggestion}s the podcaster should confirm.
 *
 * @param created     new PUBLISHED refs for previously-unseen GUIDs (case 1)
 * @param updated     known GUIDs whose relations/snapshot were refreshed (case 2)
 * @param withdrawn   refs whose GUID vanished from the feed, set WITHDRAWN (case 3)
 * @param bound       PLANNED refs auto-bound to a feed item by matching season/episode (§5.3)
 * @param suggestions fuzzy-title binding proposals awaiting confirmation (never auto-applied, §5.3)
 */
public record ReconcileResult(int created, int updated, int withdrawn, int bound, List<Suggestion> suggestions) {

    public ReconcileResult {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /**
     * A proposed PLANNED→feed-item binding based on fuzzy title similarity — a suggestion only (§5.3).
     *
     * @param plannedRefId the PLANNED ref that might correspond to the item
     * @param plannedTitle its provisional title
     * @param rawGuid      the feed item's GUID
     * @param rawTitle     the feed item's title
     * @param similarity   the normalized Jaro-Winkler similarity in {@code [0,1]}
     */
    public record Suggestion(
            UUID plannedRefId,
            String plannedTitle,
            String rawGuid,
            String rawTitle,
            double similarity) {
    }
}
