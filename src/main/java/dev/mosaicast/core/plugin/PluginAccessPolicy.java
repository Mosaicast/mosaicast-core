// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides who may read from and write to a plugin's generic doc-store surface (ARCHITECTURE §7.2/§7.6),
 * from the floors the manifest <strong>declares</strong> in its {@code data} block.
 *
 * <p>It used to derive them from slot {@code visibleTo}, taking the <em>minimum</em> across all slots as the
 * read floor. That coupled two unrelated decisions: a plugin with one anonymous display slot and one
 * admin-only slot served its whole doc store anonymously, including whatever the admin surface had written.
 * Which UI regions a plugin mounts into says nothing about who should read its data, and a plugin could not
 * separate the two without giving up one of them.
 *
 * <p>Absent declaration means the <em>closed</em> answer — reads default to the write floor, and the write
 * floor to {@code podcaster} — so a manifest that says nothing is not thereby public. Slot {@code visibleTo}
 * now governs rendering only.
 *
 * <p><strong>The floors decide access per plugin, not per document.</strong> Clearing the write floor grants
 * every key in every <em>shared</em> scope. Two things narrow that. Another user's data lives in the
 * {@code USER} scope, which no floor opens and no request can name ({@link PluginDataController}). And a
 * manifest may reserve the keys its own backend authors — {@link #backendOwnedBy} — which is the one
 * <em>per-key</em> rule on this surface, and the only reason a plugin can compute a value and have it still
 * be that value when a visitor reads it.
 */
final class PluginAccessPolicy {

    /** Privilege ranks: higher is more privileged. Anonymous is 0 (no role). */
    private static final int ANONYMOUS = 0;
    private static final int FAN = 1;
    private static final int PODCASTER = 2;
    private static final int ADMIN = 3;

    private PluginAccessPolicy() {
    }

    /** True if the (possibly absent) role may read this plugin's data. */
    static boolean canRead(PluginManifest manifest, Optional<Role> role) {
        return rank(role) >= readFloor(manifest);
    }

    /** True if the (possibly absent) role may write this plugin's data. */
    static boolean canWrite(PluginManifest manifest, Optional<Role> role) {
        return rank(role) >= writeFloor(manifest);
    }

    /**
     * The declared {@code data.backendOwned} pattern that reserves this key, if any — a client {@code PUT} or
     * {@code DELETE} to it must be refused, whatever the caller's role.
     *
     * <p>The floors say <em>who</em>; this says <em>which key</em>. Authorization on the doc store is per
     * plugin, so without it every caller above the write floor could overwrite a value the plugin's backend
     * computed — the host cannot tell a scheduled write from a {@code curl}. Reads are untouched: a reserved
     * key is still governed by {@code readableBy}, which is the point of reserving it rather than hiding it.
     *
     * <p><strong>Never applies to {@code USER}.</strong> A backend cannot write a user partition at all
     * ({@code DocStoreImpl} refuses all four methods there), so reserving one would reserve it for nobody and
     * lock the owner out of their own data. A bare {@code *} included.
     *
     * <p>Matching is by <strong>key alone</strong>, across every shared scope — hence the {@link ScopeType}
     * parameter rather than a whole scope: a plugin's aggregate may live at site level and its per-episode
     * counters at episode level under one declaration. It is also <strong>case-sensitive</strong>, unlike the
     * floors right above, which lowercase because a role name is a closed vocabulary and a key is not.
     *
     * @return the pattern that reserved the key, so the refusal can name the rule; empty if none does
     */
    static Optional<String> backendOwnedBy(PluginManifest manifest, ScopeType scope, String key) {
        if (scope == ScopeType.USER || key == null) {
            return Optional.empty();
        }
        for (String pattern : manifest.dataOrDefault().backendOwnedOrEmpty()) {
            // A bare "*" needs no special case: it is the empty prefix, and every key starts with that.
            boolean matches = pattern.endsWith("*")
                    ? key.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(key);
            if (matches) {
                return Optional.of(pattern);
            }
        }
        return Optional.empty();
    }

    private static int readFloor(PluginManifest manifest) {
        return rank(manifest.dataOrDefault().readableByOrDefault());
    }

    private static int writeFloor(PluginManifest manifest) {
        return rank(manifest.dataOrDefault().writableByOrDefault());
    }

    private static int rank(Optional<Role> role) {
        return rankOf(role);
    }

    /**
     * The caller's privilege rank, for policies that live outside this class.
     *
     * <p>Exposed so {@link PluginNavService} can apply the {@code visibleTo} floor to navigation entries
     * without a second copy of the ranking. It deliberately does <em>not</em> expose the
     * {@code visibleTo}-string mapping: the two policies differ on an unrecognised value, and sharing that
     * half would quietly give one of them the other's behaviour.
     */
    static int rankOf(Optional<Role> role) {
        return role.map(PluginAccessPolicy::rank).orElse(ANONYMOUS);
    }

    /**
     * The rank a caller needs to be shown a {@code visibleTo} entrance — a nav entry or a slot.
     *
     * <p>An unrecognised value resolves to {@code podcaster}, not to anonymous: a typo in a manifest should
     * hide an entrance rather than publish one. That is what separates this from the {@code visibleTo}
     * mapping used for the data floors right below, which fails open — the comment on {@link #rankOf}
     * explains why those two must not be one method, and this is not that sharing. Navigation and slots are
     * the same policy for the same field, and were two copies of it until a slot needed it server-side.
     *
     * <p>{@code Locale.ROOT}, because the default locale is the host environment's: on a Turkish JVM
     * {@code "ANONYMOUS".toLowerCase(Locale.ROOT)} is {@code "anonymous"} with a dotless i, which matches nothing and
     * would silently promote every entrance to podcaster-only.
     */
    static int visibilityFloorOf(String visibleTo) {
        if (visibleTo == null || visibleTo.isBlank()) {
            return ANONYMOUS;
        }
        return switch (visibleTo.toLowerCase(Locale.ROOT)) {
            case "anonymous" -> ANONYMOUS;
            case "fan" -> FAN;
            case "admin" -> ADMIN;
            default -> PODCASTER;
        };
    }

    /**
     * Whether the (possibly absent) role meets a declared role-name floor — {@code external.usedBy} (§16).
     *
     * <p>Its own method rather than a reuse of the {@code visibleTo} mapping right below, which is private
     * for the reason this comment exists: the two policies differ on an unrecognised value, and this one has
     * no unrecognised value to differ about. {@code usedBy} is checked against a closed vocabulary at load,
     * so a manifest that reached here spelled a real role, and an unknown name arriving anyway is a bug in
     * validation rather than a floor to silently read as {@code anonymous}.
     *
     * @throws IllegalArgumentException if the floor is not a known role name
     */
    static boolean meetsFloor(String floor, Optional<Role> role) {
        int required = switch (floor == null ? "" : floor.toLowerCase(Locale.ROOT)) {
            case "anonymous" -> ANONYMOUS;
            case "fan" -> FAN;
            case "podcaster" -> PODCASTER;
            case "admin" -> ADMIN;
            default -> throw new IllegalArgumentException("not a role name: " + floor);
        };
        return rankOf(role) >= required;
    }

    private static int rank(Role role) {
        return switch (role) {
            case ADMIN -> ADMIN;
            case PODCASTER -> PODCASTER;
            case FAN -> FAN;
        };
    }

    private static int rank(String visibleTo) {
        if (visibleTo == null) {
            return ANONYMOUS;
        }
        return switch (visibleTo.toLowerCase(Locale.ROOT)) {
            case "admin" -> ADMIN;
            case "podcaster" -> PODCASTER;
            case "fan" -> FAN;
            default -> ANONYMOUS;
        };
    }
}
