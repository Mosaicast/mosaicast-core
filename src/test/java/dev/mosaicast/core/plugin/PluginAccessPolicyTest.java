// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.plugin.PluginManifest.DataAccess;
import dev.mosaicast.core.plugin.PluginManifest.Slot;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginAccessPolicy} (ARCHITECTURE §7.2/§7.6).
 *
 * <p>The floors are now <strong>declared</strong> in the manifest's {@code data} block. They used to be
 * derived from slot {@code visibleTo} — the read floor being the minimum across all slots — which meant a
 * plugin could not have an anonymous card and a private data surface at the same time. Most of this file is
 * about that separation now holding.
 *
 * <p>The second half covers {@code data.backendOwned} — the only rule here that looks at the <em>key</em>
 * rather than the caller.
 */
class PluginAccessPolicyTest {

    /** Sample-shaped: an anonymous card and a podcaster admin slot, with a public read floor declared. */
    private final PluginManifest publiclyReadable = manifest(
            new DataAccess("anonymous", "podcaster", null),
            new Slot("episode", "card", "main", "anonymous", 100),
            new Slot("site", "admin", "sidebar", "podcaster", null));

    @Test
    void anonymousMayReadButNotWrite() {
        assertThat(PluginAccessPolicy.canRead(publiclyReadable, Optional.empty())).isTrue();
        assertThat(PluginAccessPolicy.canWrite(publiclyReadable, Optional.empty())).isFalse();
    }

    @Test
    void fanMayReadButNotWrite() {
        assertThat(PluginAccessPolicy.canRead(publiclyReadable, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(publiclyReadable, Optional.of(Role.FAN))).isFalse();
    }

    @Test
    void podcasterAndAdminMayWrite() {
        assertThat(PluginAccessPolicy.canWrite(publiclyReadable, Optional.of(Role.PODCASTER))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(publiclyReadable, Optional.of(Role.ADMIN))).isTrue();
    }

    @Test
    void aFanWriteFloorAdmitsFanWrites() {
        PluginManifest fanFacing = manifest(new DataAccess("anonymous", "fan", null),
                new Slot("episode", "board", "main", "fan", null));
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.empty())).isFalse();
    }

    @Test
    void anAnonymousSlotNoLongerOpensTheDataSurface() {
        // The finding this replaces. The read floor was the minimum `visibleTo` across all slots, so one
        // anonymous display slot served the plugin's entire store anonymously — including whatever the
        // admin-only slot beside it had written. Rendering and data access are separate decisions now.
        PluginManifest privateData = manifest(new DataAccess("podcaster", "podcaster", null),
                new Slot("episode", "card", "main", "anonymous", 100),
                new Slot("site", "admin", "sidebar", "admin", null));

        assertThat(PluginAccessPolicy.canRead(privateData, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canRead(privateData, Optional.of(Role.FAN))).isFalse();
        assertThat(PluginAccessPolicy.canRead(privateData, Optional.of(Role.PODCASTER))).isTrue();
    }

    @Test
    void sayingNothingGetsTheClosedAnswer() {
        // Absent `data` used to mean "inherit from slots", which for an anonymous slot meant public. It now
        // means the write floor for reads and podcaster for writes: a manifest that declares nothing is not
        // thereby publishing its store.
        PluginManifest undeclared = manifest(null,
                new Slot("episode", "card", "main", "anonymous", 100));

        assertThat(PluginAccessPolicy.canRead(undeclared, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canRead(undeclared, Optional.of(Role.FAN))).isFalse();
        assertThat(PluginAccessPolicy.canRead(undeclared, Optional.of(Role.PODCASTER))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(undeclared, Optional.of(Role.PODCASTER))).isTrue();
    }

    @Test
    void anAbsentReadFloorFollowsTheWriteFloorRatherThanOpening() {
        PluginManifest writeOnlyDeclared = manifest(new DataAccess(null, "fan", null),
                new Slot("episode", "card", "main", "anonymous", 100));

        assertThat(PluginAccessPolicy.canRead(writeOnlyDeclared, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canRead(writeOnlyDeclared, Optional.of(Role.FAN))).isTrue();
    }

    @Test
    void aPluginWithNoSlotsAtAllStillHasFloors() {
        PluginManifest headless = new PluginManifest("p", "1.0.0", "0.6.0", "P", null, null, null, "doc",
                null, new DataAccess("fan", "admin", null), null);

        assertThat(PluginAccessPolicy.canRead(headless, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canRead(headless, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canWrite(headless, Optional.of(Role.PODCASTER))).isFalse();
        assertThat(PluginAccessPolicy.canWrite(headless, Optional.of(Role.ADMIN))).isTrue();
    }

    // --- data.backendOwned: the one per-key rule on this surface (§7.2/§7.6) ---

    @Test
    void aDeclaredKeyIsReservedAndAnUndeclaredOneIsNot() {
        PluginManifest m = reserving("stats", "favourites");

        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "stats")).contains("stats");
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "highlight")).isEmpty();
    }

    @Test
    void aTrailingStarReservesThePrefixAndNothingElse() {
        PluginManifest m = reserving("agg:*");

        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.FEED, "agg:")).contains("agg:*");
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.FEED, "agg:total")).contains("agg:*");
        // The separator is part of the prefix: `aggregate` is a different key, not a longer one.
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.FEED, "aggregate")).isEmpty();
    }

    @Test
    void aBareStarReservesEveryKey() {
        PluginManifest m = reserving("*");

        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "anything")).contains("*");
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.EPISODE, "")).contains("*");
    }

    @Test
    void theUserScopeIsExemptEvenUnderABareStar() {
        // A backend cannot write a USER partition at all, so reserving one would reserve it for nobody and
        // lock its owner out of their own data.
        assertThat(PluginAccessPolicy.backendOwnedBy(reserving("*"), ScopeType.USER, "fav:ep-1")).isEmpty();
        assertThat(PluginAccessPolicy.backendOwnedBy(reserving("stats"), ScopeType.USER, "stats")).isEmpty();
    }

    @Test
    void reservationIsCaseSensitive() {
        // Unlike the role floors beside it: a role name is a closed vocabulary, a key is not.
        PluginManifest m = reserving("stats");

        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "Stats")).isEmpty();
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "STATS")).isEmpty();
    }

    @Test
    void reservationIsByKeyAcrossEveryScopeItCouldLiveIn() {
        // The sample's shape: one declaration covering an aggregate at site level and a counter at episode
        // level. Matching on the scope as well would need the plugin to say the same thing four times.
        PluginManifest m = reserving("favourites");

        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SITE, "favourites")).isPresent();
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.FEED, "favourites")).isPresent();
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.SEASON, "favourites")).isPresent();
        assertThat(PluginAccessPolicy.backendOwnedBy(m, ScopeType.EPISODE, "favourites")).isPresent();
    }

    @Test
    void sayingNothingReservesNothing() {
        assertThat(PluginAccessPolicy.backendOwnedBy(publiclyReadable, ScopeType.SITE, "stats")).isEmpty();
        assertThat(PluginAccessPolicy.backendOwnedBy(manifest(null), ScopeType.SITE, "stats")).isEmpty();
        assertThat(PluginAccessPolicy.backendOwnedBy(reserving(), ScopeType.SITE, "stats")).isEmpty();
    }

    @Test
    void aReservationDoesNotChangeWhoMayReadOrWriteAnythingElse() {
        // Reserving a key publishes it read-only; it does not move the floors it sits under.
        PluginManifest m = reserving("stats");

        assertThat(PluginAccessPolicy.canRead(m, Optional.empty())).isTrue();
        assertThat(PluginAccessPolicy.canWrite(m, Optional.of(Role.FAN))).isFalse();
        assertThat(PluginAccessPolicy.canWrite(m, Optional.of(Role.PODCASTER))).isTrue();
    }

    /** Sample-shaped floors (anonymous reads, podcaster writes) with the given keys reserved. */
    private static PluginManifest reserving(String... backendOwned) {
        return manifest(new DataAccess("anonymous", "podcaster", List.of(backendOwned)));
    }

    private static PluginManifest manifest(DataAccess data, Slot... slots) {
        return new PluginManifest("p", "1.0.0", "0.6.0", "P", null, null, List.of(slots), "doc", null,
                data, null);
    }
}
