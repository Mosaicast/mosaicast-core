// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.Locale;

/**
 * Reading a role name off the wire (ARCHITECTURE §8.5).
 *
 * <p>One place, because there were two and they disagreed on more than they should have. The admin
 * controller threw {@code ConflictException} → <strong>409</strong> for an unknown name and the dev-login
 * controller {@code IllegalArgumentException} → <strong>400</strong>, so the same bad input produced two
 * different statuses depending on the route — and 409 is wrong here in any case: there is no state
 * conflict, only a client that sent a word this host does not know. 409 on that endpoint is reserved for
 * the real ones, demoting the last admin and changing your own role.
 *
 * <p>{@code Locale.ROOT} is the other half. The default locale comes from the host environment, and on a
 * Turkish JVM {@code "admin".toUpperCase()} is {@code "ADMİN"} — a dotted capital I that {@code
 * Role.valueOf} does not know, so <em>every</em> role change on that server failed with "Unknown role".
 * That is the shape of a defect a self-hosted project cannot test its way out of by running on its own
 * machines (core#197).
 */
public final class Roles {

    private Roles() {
    }

    /**
     * The role named by {@code value}, case-insensitively and locale-independently.
     *
     * @throws IllegalArgumentException if it names no role — a client error, and mapped to 400
     */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("No role given. Use one of: " + names());
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + value + "'. Use one of: " + names(), e);
        }
    }

    private static String names() {
        return String.join(", ", List.of(Role.values()).stream()
                .map(role -> role.name().toLowerCase(Locale.ROOT))
                .toList());
    }
}
