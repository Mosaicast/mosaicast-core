// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.UUID;

/**
 * A resolved storage address: the scope type plus the <em>partition id the row is actually keyed by</em>.
 *
 * <p>Distinct from the SDK's {@link Scope} because the two are not the same thing for a {@code USER} scope,
 * and conflating them is how the IDOR would come back. {@code Scope}'s canonical constructor pins every
 * {@code USER} id to the sentinel {@code "me"} — deliberately, since that is what makes another person's
 * partition <em>inexpressible</em> in plugin-facing code rather than merely refused. The consequence is that
 * a {@code Scope} cannot carry "user X's partition" at all, and the host needs to, because that is what it
 * writes into {@code plugin_data.scope_id}.
 *
 * <p>So the boundary is here: a {@code Scope} is what a plugin or a request <em>asks for</em>, a
 * {@code DataScope} is what the host <em>resolved it to</em>. The only way to build a user partition is
 * {@link #ofUser(UUID)} with an id the host obtained from the session — there is no path from a request
 * string to this field.
 *
 * @param type        the scope level
 * @param partitionId the value stored in {@code scope_id}: a slug for entity scopes, the site singleton, or
 *                    a user's UUID for {@link ScopeType#USER}
 */
record DataScope(ScopeType type, String partitionId) {

    /**
     * The stored spelling of the {@code USER} scope type.
     *
     * <p>Exists because it was written out by hand in two queries and got the case wrong in both, while a
     * third derived it correctly — so the three drifted apart immediately and silently. Everything that
     * needs this value now reads it from here.
     */
    static final String USER_TYPE = ScopeType.USER.name().toLowerCase(java.util.Locale.ROOT);

    /** An entity scope, exactly as addressed. Canonicalisation to the slug form happens in the service. */
    static DataScope of(Scope scope) {
        return new DataScope(scope.type(), scope.id());
    }

    /** The partition belonging to one user. The caller must have resolved {@code userId} from the session. */
    static DataScope ofUser(UUID userId) {
        return new DataScope(ScopeType.USER, userId.toString());
    }

    /**
     * The lower-case scope type as stored in {@code plugin_data.scope_type}.
     *
     * <p>{@code Locale.ROOT} rather than the default locale: {@code EPISODE} lower-cases to
     * {@code epısode} under a Turkish default, which would write a scope type nothing can read back.
     */
    String typeColumn() {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }
}
