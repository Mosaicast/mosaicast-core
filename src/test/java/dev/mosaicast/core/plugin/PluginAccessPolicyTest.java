// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.plugin.PluginManifest.Slot;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginAccessPolicy} (ARCHITECTURE §7.5/§7.6). A sample-shaped plugin (an
 * anonymous-visible card slot + a podcaster-only admin slot) is publicly readable, but writes need a
 * podcaster (or admin) — never anonymous, never a fan.
 */
class PluginAccessPolicyTest {

    private final PluginManifest sampleShaped = manifest(
            new Slot("episode", "card", "main", "anonymous", 100),
            new Slot("site", "admin", "sidebar", "podcaster", null));

    @Test
    void anonymousMayReadButNotWrite() {
        assertThat(PluginAccessPolicy.canRead(sampleShaped, Optional.empty())).isTrue();
        assertThat(PluginAccessPolicy.canWrite(sampleShaped, Optional.empty())).isFalse();
    }

    @Test
    void fanMayReadButNotWrite() {
        assertThat(PluginAccessPolicy.canRead(sampleShaped, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(sampleShaped, Optional.of(Role.FAN))).isFalse();
    }

    @Test
    void podcasterAndAdminMayWrite() {
        assertThat(PluginAccessPolicy.canWrite(sampleShaped, Optional.of(Role.PODCASTER))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(sampleShaped, Optional.of(Role.ADMIN))).isTrue();
    }

    @Test
    void fanSlotAdmitsFanWrites() {
        PluginManifest fanFacing = manifest(new Slot("episode", "board", "main", "fan", null));
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.of(Role.FAN))).isTrue();
        assertThat(PluginAccessPolicy.canWrite(fanFacing, Optional.empty())).isFalse();
    }

    private static PluginManifest manifest(Slot... slots) {
        return new PluginManifest("p", "1.0.0", "0.4.0", "P", null, null, List.of(slots), "doc", null, null);
    }
}
