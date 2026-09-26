// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PluginConfigImplTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void aStoredValueThatBreaksALaterBoundCountsAsUnset() {
        // Saved as 0 under SDK 0.15, when that was legal; the plugin now declares "min": 1. SDK 0.16.0
        // promises the plugin what it reads satisfies its declaration, so it needs no clamp of its own.
        PluginManifest.ConfigField interval = new PluginManifest.ConfigField("number", json.readTree("30"),
                "podcaster", List.of(), null, null, BigDecimal.ONE, null, null, null, null);
        PluginSettingsService settings = mock(PluginSettingsService.class);
        when(settings.config("bingo")).thenReturn(Map.of("interval", json.readTree("0")));

        PluginConfigImpl config = new PluginConfigImpl("bingo", Map.of("interval", interval), settings, json);

        assertThat(config.get("interval", Integer.class)).contains(30);
    }

    @Test
    void aStoredValueInsideTheBoundsIsRead() {
        PluginManifest.ConfigField interval = new PluginManifest.ConfigField("number", json.readTree("30"),
                "podcaster", List.of(), null, null, BigDecimal.ONE, null, null, null, null);
        PluginSettingsService settings = mock(PluginSettingsService.class);
        when(settings.config("bingo")).thenReturn(Map.of("interval", json.readTree("5")));

        assertThat(new PluginConfigImpl("bingo", Map.of("interval", interval), settings, json)
                .get("interval", Integer.class)).contains(5);
    }
}
