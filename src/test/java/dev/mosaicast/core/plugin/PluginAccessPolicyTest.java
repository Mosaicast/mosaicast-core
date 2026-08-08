// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.plugin.PluginManifest.DataAccess;
import dev.mosaicast.core.plugin.PluginManifest.Slot;
import dev.mosaicast.plugin.api.Role;
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
 */
class PluginAccessPolicyTest {

    /** Sample-shaped: an anonymous card and a podcaster admin slot, with a public read floor declared. */
    private final PluginManifest publiclyReadable = manifest(
            new DataAccess("anonymous", "podcaster"),
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
        PluginManifest fanFacing = manifest(new DataAccess("anonymous", "fan"),
                new Slot("episode", "board", "main", "fan", null));
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.empty())).isFalse();
    }

    @Test
    void anAnonymousSlotNoLongerOpensTheDataSurface() {
        // The finding this replaces. The read floor was the minimum `visibleTo` across all slots, so one
        // anonymous display slot served the plugin's entire store anonymously — including whatever the
        // admin-only slot beside it had written. Rendering and data access are separate decisions now.
        PluginManifest privateData = manifest(new DataAccess("podcaster", "podcaster"),
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
        PluginManifest writeOnlyDeclared = manifest(new DataAccess(null, "fan"),
                new Slot("episode", "card", "main", "anonymous", 100));

        assertThat(PluginAccessPolicy.canRead(writeOnlyDeclared, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canRead(writeOnlyDeclared, Optional.of(Role.FAN))).isTrue();
    }

    @Test
    void aPluginWithNoSlotsAtAllStillHasFloors() {
        PluginManifest headless = new PluginManifest("p", "1.0.0", "0.5.0", "P", null, null, null, "doc",
                null, new DataAccess("fan", "admin"), null);

        assertThat(PluginAccessPolicy.canRead(headless, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canRead(headless, Optional.empty())).isFalse();
        assertThat(PluginAccessPolicy.canWrite(headless, Optional.of(Role.PODCASTER))).isFalse();
        assertThat(PluginAccessPolicy.canWrite(headless, Optional.of(Role.ADMIN))).isTrue();
    }

    private static PluginManifest manifest(DataAccess data, Slot... slots) {
        return new PluginManifest("p", "1.0.0", "0.5.0", "P", null, null, List.of(slots), "doc", null,
                data, null);
    }
}
