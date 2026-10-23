// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.util.UUID;

/**
 * What one user may do changed — their role was set, or their account was erased.
 *
 * <p>A request needs no telling: {@code AuthenticatedUserFilter} reloads the user on every one. What does
 * need it is a connection that outlives the request that opened it — a plugin channel stream (§7.9) — which
 * §8.5 says must stop on a role change at once, not when it next happens to ask.
 *
 * @param userId the user whose access changed
 */
public record UserAccessChangedEvent(UUID userId) {
}
