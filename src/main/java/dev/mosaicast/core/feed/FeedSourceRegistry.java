// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link FeedSource} implementation for a feed's {@code type} (ARCHITECTURE §5.1). New
 * source types (Patreon in v2, a native host later) register simply by being Spring beans — the rest of
 * the platform keeps reasoning about capabilities, not types.
 */
@Component
public class FeedSourceRegistry {

    private final Map<String, FeedSource> byType;

    public FeedSourceRegistry(List<FeedSource> sources) {
        this.byType = sources.stream().collect(Collectors.toMap(FeedSource::type, Function.identity()));
    }

    /** The source for a type, or empty if none is registered (e.g. the {@code manual} feed has none). */
    public Optional<FeedSource> forType(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
