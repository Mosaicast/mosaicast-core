// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.SearchHit;
import dev.mosaicast.plugin.api.SearchProvider;
import java.util.ArrayList;
import java.util.List;
import org.pf4j.Extension;

/**
 * The fixture's optional search extension (ARCHITECTURE §6, SDK {@code SearchProvider}).
 *
 * <p>Deliberately exercises the three things the host is responsible for: a hit whose subpath tries to
 * escape the plugin's own namespace, a hit only a signed-in caller should see (the plugin filters, because
 * the host has no model of its objects), and — for the query {@code slow} — a provider that blows its
 * budget, so the host's timeout has something to actually time out.
 */
@Extension
public class FixtureSearch implements SearchProvider {

    @Override
    public List<SearchHit> search(String query, Role role, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if ("slow".equals(query)) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
        if (!query.toLowerCase().contains("kraken")) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        hits.add(new SearchHit("glossary/kraken", "The Kraken", "A very large squid.", 1.0));
        // Escaping is the host's to prevent, not the plugin's to promise.
        hits.add(new SearchHit("../../admin/feeds", "Not mine", "", 0.9));
        if (role != null) {
            hits.add(new SearchHit("drafts/kraken", "Kraken (draft)", "Only for signed-in callers.", 0.5));
        }
        return hits;
    }
}
