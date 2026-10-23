// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.Previews;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resolves the shared scope an action or a channel names, for one caller (§7.9): it must exist and be
 * visible to them — a quiet planned episode only to a podcaster or admin (§4.3) — and comes back in its
 * canonical slug form, so two callers naming one episode by id and by slug reach the same channel.
 *
 * <p>Everything that fails is a 404, never "you may not": an episode this caller cannot see does not exist
 * for them, which is the rule {@code ctx.feeds} follows (§7.5). {@code USER} is not a shared scope and is
 * refused the same way — a channel or an action never lives in someone's partition.
 */
@Component
class SharedScopes {

    private final FeedAccessImpl scopes;
    private final ScopeIds scopeIds;

    SharedScopes(FeedAccessImpl scopes, ScopeIds scopeIds) {
        this.scopes = scopes;
        this.scopeIds = scopeIds;
    }

    Scope resolve(String type, String id, Authentication authentication) {
        ScopeType scopeType;
        try {
            scopeType = ScopeType.valueOf(type == null ? "" : type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope type: " + type);
        }
        if (scopeType == ScopeType.USER || id == null || id.isBlank()) {
            throw new NotFoundException("Unknown scope: " + type + "/" + id);
        }
        Scope scope;
        try {
            scope = new Scope(scopeType, id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope: " + type + "/" + id);
        }
        if (!scopes.exists(scope, Previews.canSeeQuiet(authentication))) {
            throw new NotFoundException("Unknown scope: " + type + "/" + id);
        }
        return new Scope(scopeType, scopeIds.canonical(scope));
    }
}
