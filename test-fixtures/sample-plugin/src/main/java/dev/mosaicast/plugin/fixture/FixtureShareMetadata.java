// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.OgMeta;
import dev.mosaicast.plugin.api.ShareMetadataProvider;
import java.util.Optional;
import org.pf4j.Extension;

/**
 * The fixture's optional share-metadata extension (ARCHITECTURE §6.4/§7.4), so the host's deep-link tests
 * exercise a real PF4J extension rather than a mock: a known subpath gets its own preview, anything else
 * falls back to the host's site-level OpenGraph.
 */
@Extension
public class FixtureShareMetadata implements ShareMetadataProvider {

    @Override
    public Optional<OgMeta> metaFor(String subpath) {
        if ("shared".equals(subpath)) {
            return Optional.of(new OgMeta("Fixture shared page", "A page shared from the fixture", null));
        }
        // A page written in one fixed language whoever asks for it (SDK 0.12.0) — the case where the host's
        // request-resolved locale would be wrong, and the plugin says so.
        if ("geteilt".equals(subpath)) {
            return Optional.of(new OgMeta("Geteilte Fixture-Seite", "Eine Seite aus dem Fixture", null, "de"));
        }
        return Optional.empty();
    }
}
