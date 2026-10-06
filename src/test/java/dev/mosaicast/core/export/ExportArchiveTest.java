// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserNameHistoryRepository;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenRepository;
import dev.mosaicast.core.plugin.PluginDataService;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.plugin.api.ExportFile;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.UserDataHandler;
import dev.mosaicast.plugin.api.UserExport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-plugin outcomes of an export (ARCHITECTURE §12.8.1), with the plugins stubbed so each can be made to
 * behave one way: no handler, the map form only, too large, too slow.
 */
class ExportArchiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PluginLoaderService plugins = mock(PluginLoaderService.class);
    private final PluginExtensions extensions = mock(PluginExtensions.class);
    private final UserDataExportPartRepository parts = mock(UserDataExportPartRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final List<PluginRegistration> registered = new ArrayList<>();
    private UserDataExport export;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        UUID userId = UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(user.getRole()).thenReturn(Role.FAN);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(plugins.all()).thenReturn(registered);
        when(parts.save(any(UserDataExportPart.class))).thenAnswer(inv -> inv.getArgument(0));
        export = UserDataExport.requested(userId, Instant.now());
    }

    @Test
    void eachWayAPluginCanAnswerIsNamedApart() throws Exception {
        plugin("silent");                                     // no handler at all
        plugin("mapform", new UserDataHandler() {             // only the 0.18 map form
            @Override
            public void eraseUser(String userId) {
            }

            @Override
            public Optional<Map<String, Object>> exportUser(String userId) {
                return Optional.of(Map.of("cards", 3));
            }
        });
        plugin("nothing", erasingOnly());                     // a handler with nothing on this person
        plugin("huge", files(ExportFile.text("big.bin", "application/octet-stream", "x".repeat(2048))));
        plugin("slow", new UserDataHandler() {
            @Override
            public void eraseUser(String userId) {
            }

            @Override
            public Optional<UserExport> exportFiles(String userId) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        });

        Map<String, String> zip = run(new ExportProperties(null, null, 1024L, Duration.ofMillis(300)));
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (JsonNode plugin : JSON.readTree(zip.get("outcome.json")).get("plugins")) {
            outcomes.put(plugin.get("plugin").asString(), plugin.get("outcome").asString());
        }

        assertThat(outcomes).containsEntry("silent", "not-supported")
                .containsEntry("mapform", "complete")
                .containsEntry("nothing", "empty")
                .containsEntry("huge", "failed")
                .containsEntry("slow", "failed");
        // The map form is written by the host as data.json; an oversized part is never written truncated.
        assertThat(zip.get("plugins/mapform/data.json")).contains("\"cards\" : 3");
        assertThat(zip.keySet()).noneMatch(path -> path.startsWith("plugins/huge/"));
    }

    @Test
    void theOperatorMayLowerTheSdkLimitsButNeverRaiseThem() {
        ExportProperties raised = new ExportProperties(null, null, UserExport.MAX_BYTES * 2, Duration.ofHours(1));
        assertThat(raised.pluginMaxBytes()).isEqualTo(UserExport.MAX_BYTES);
        assertThat(raised.pluginTimeout()).isEqualTo(UserExport.TIMEOUT);
        ExportProperties lowered = new ExportProperties(null, null, 1024L, Duration.ofSeconds(5));
        assertThat(lowered.pluginMaxBytes()).isEqualTo(1024L);
        assertThat(lowered.pluginTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    // ---- helpers ----

    private void plugin(String id, UserDataHandler... handlers) {
        PluginRegistration registration = new PluginRegistration(id, PluginRegistration.Status.LOADED, null, null,
                null);
        registered.add(registration);
        when(plugins.active(id)).thenReturn(Optional.of(registration));
        when(extensions.userDataHandlers(id)).thenReturn(List.of(handlers));
    }

    private static UserDataHandler erasingOnly() {
        return userId -> {
        };
    }

    private static UserDataHandler files(ExportFile... files) {
        return new UserDataHandler() {
            @Override
            public void eraseUser(String userId) {
            }

            @Override
            public Optional<UserExport> exportFiles(String userId) {
                return Optional.of(UserExport.of(files));
            }
        };
    }

    private Map<String, String> run(ExportProperties properties) throws Exception {
        ExportArchive archive = new ExportArchive(plugins, extensions, mock(PluginDataService.class), parts, users,
                mock(LinkedIdentityRepository.class), mock(UserNameHistoryRepository.class),
                mock(PersonalAccessTokenRepository.class), mock(JdbcTemplate.class), JSON, properties);
        Path file = archive.build(export);
        try {
            Map<String, String> entries = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return entries;
        } finally {
            Files.deleteIfExists(file);
            archive.shutdown();
        }
    }
}
