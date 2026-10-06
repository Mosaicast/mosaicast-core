// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserNameHistoryRepository;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenRepository;
import dev.mosaicast.core.export.UserDataExportPart.Outcome;
import dev.mosaicast.core.plugin.PluginDataService;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.plugin.api.ExportFile;
import dev.mosaicast.plugin.api.UserDataHandler;
import dev.mosaicast.plugin.api.UserExport;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds one person's export archive (ARCHITECTURE §12.8.1): what core holds, then each plugin's part, then
 * {@code outcome.json} — into a temporary file, never into memory as a whole.
 *
 * <p><strong>Every plugin that could hold data gets its outcome recorded before it is asked</strong>, the way
 * erasure records its debts (§12.8): a plugin that throws, runs out of time or cannot be asked at all is a
 * named outcome in the archive and in admin, never a gap nobody notices. The order is the SDK's —
 * {@code exportFiles}, then {@code exportUser} written as {@code data.json}, then "holds nothing".
 */
@Component
public class ExportArchive {

    private static final Logger log = LoggerFactory.getLogger(ExportArchive.class);

    private final PluginLoaderService plugins;
    private final PluginExtensions extensions;
    private final PluginDataService pluginData;
    private final UserDataExportPartRepository parts;
    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final UserNameHistoryRepository nameHistory;
    private final PersonalAccessTokenRepository tokens;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ExportProperties properties;

    /** One virtual thread per plugin call, so a plugin that ignores its interrupt costs a thread, not the job. */
    private final ExecutorService pluginThreads = Executors.newVirtualThreadPerTaskExecutor();

    public ExportArchive(PluginLoaderService plugins, PluginExtensions extensions, PluginDataService pluginData,
                         UserDataExportPartRepository parts, UserRepository users,
                         LinkedIdentityRepository identities, UserNameHistoryRepository nameHistory,
                         PersonalAccessTokenRepository tokens, JdbcTemplate jdbc, ObjectMapper json,
                         ExportProperties properties) {
        this.plugins = plugins;
        this.extensions = extensions;
        this.pluginData = pluginData;
        this.parts = parts;
        this.users = users;
        this.identities = identities;
        this.nameHistory = nameHistory;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        pluginThreads.shutdownNow();
    }

    /**
     * Writes the archive for one export.
     *
     * @return the temporary file holding it; the caller stores and deletes it
     * @throws IOException if the archive cannot be written at all
     */
    public Path build(UserDataExport export) throws IOException {
        UUID userId = export.getUserId();
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("the account no longer exists"));

        // Recorded first, all of them, before any plugin is asked (§12.8.1).
        List<UserDataExportPart> owed = new ArrayList<>();
        for (PluginRegistration registration : plugins.all()) {
            if (mayHoldData(registration)) {
                owed.add(parts.save(UserDataExportPart.pending(export.getId(), registration.id())));
            }
        }

        Path file = Files.createTempFile("mosaicast-export-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
            putJson(zip, "core/account.json", account(user));
            putJson(zip, "core/listening.json", listening(userId));
            putJson(zip, "core/notifications.json", notifications(userId));
            for (Map.Entry<String, Map<String, Object>> plugin : userDocuments(userId).entrySet()) {
                putJson(zip, "core/plugin-documents/" + plugin.getKey() + ".json", plugin.getValue());
            }
            List<Map<String, Object>> outcomes = new ArrayList<>();
            for (UserDataExportPart part : owed) {
                askPlugin(part, userId.toString(), zip);
                parts.save(part);
                Map<String, Object> outcome = new LinkedHashMap<>();
                outcome.put("plugin", part.getPluginId());
                outcome.put("outcome", part.getOutcome().wireName());
                if (part.getDetail() != null) {
                    outcome.put("detail", part.getDetail());
                }
                if (part.getBytes() != null) {
                    outcome.put("bytes", part.getBytes());
                }
                outcomes.add(outcome);
            }
            putJson(zip, "outcome.json", Map.of("exportedAt", Instant.now().toString(), "plugins", outcomes));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return file;
    }

    /**
     * Whether a discovered plugin could hold anything — the same test erasure applies (§12.8): loaded and
     * switched-off plugins may, a rejected one only if it ever stored a document.
     */
    private boolean mayHoldData(PluginRegistration registration) {
        return switch (registration.status()) {
            case LOADED, DISABLED -> true;
            case REJECTED -> pluginData.hasStoredData(registration.id());
        };
    }

    /** Asks one plugin, writing its files under {@code plugins/<id>/} and settling its part. */
    private void askPlugin(UserDataExportPart part, String userId, ZipOutputStream zip) throws IOException {
        String pluginId = part.getPluginId();
        if (plugins.active(pluginId).isEmpty()) {
            part.settle(Outcome.OUTSTANDING, "the plugin is not running, so it could not be asked", null);
            return;
        }
        List<UserDataHandler> handlers = extensions.userDataHandlers(pluginId);
        if (handlers.isEmpty()) {
            part.settle(Outcome.NOT_SUPPORTED, "the plugin has no export handler", null);
            return;
        }
        Optional<UserExport> answer;
        Future<Optional<UserExport>> call = pluginThreads.submit(() -> ask(handlers, userId));
        try {
            answer = call.get(properties.pluginTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            part.settle(Outcome.FAILED, "no answer within " + properties.pluginTimeout().toSeconds() + " s", null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while asking " + pluginId, e);
        } catch (java.util.concurrent.ExecutionException e) {
            // Into the host log with the cause; the archive and admin get a sentence, not a stack trace.
            log.warn("Export handler of plugin '{}' failed", pluginId, e.getCause());
            part.settle(Outcome.FAILED, "the plugin's export handler failed", null);
            return;
        }
        if (answer.isEmpty()) {
            part.settle(Outcome.EMPTY, null, null);
            return;
        }
        long size = answer.get().totalBytes();
        if (size > properties.pluginMaxBytes()) {
            // Never truncated: a part cut short reads exactly like a complete one.
            part.settle(Outcome.FAILED, "the part was %d bytes, more than the %d allowed"
                    .formatted(size, properties.pluginMaxBytes()), size);
            return;
        }
        for (ExportFile file : answer.get().files()) {
            put(zip, "plugins/" + pluginId + "/" + file.path(), file.bytes());
        }
        part.settle(Outcome.COMPLETE, null, size);
    }

    /**
     * The SDK's order (§12.8.1): files first; else the map form, written as {@code data.json} by the host so the
     * contract never has to serialise JSON itself; else nothing.
     */
    private Optional<UserExport> ask(List<UserDataHandler> handlers, String userId) {
        List<ExportFile> files = new ArrayList<>();
        for (UserDataHandler handler : handlers) {
            Optional<UserExport> own = handler.exportFiles(userId);
            if (own.isPresent()) {
                files.addAll(own.get().files());
                continue;
            }
            Optional<Map<String, Object>> map = handler.exportUser(userId);
            if (map.isPresent() && !map.get().isEmpty()) {
                files.add(new ExportFile("data.json", "application/json",
                        json.writerWithDefaultPrettyPrinter().writeValueAsBytes(map.get())));
            }
        }
        // Two handlers in one plugin naming the same path is the plugin's bug; UserExport refuses it, which
        // lands as a failed part rather than one file silently overwriting the other.
        return files.isEmpty() ? Optional.empty() : Optional.of(new UserExport(files));
    }

    private Map<String, Object> account(User user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId().toString());
        account.put("displayName", user.getDisplayName());
        account.put("role", user.getRole().name().toLowerCase(java.util.Locale.ROOT));
        account.put("createdAt", text(user.getCreatedAt()));
        account.put("avatarFrom", user.getAvatarProvider());
        account.put("identities", identities.findByUserId(user.getId()).stream().map(identity -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("provider", identity.getProvider());
            row.put("externalId", identity.getExternalId());
            row.put("email", identity.getEmail());
            row.put("linkedAt", text(identity.getCreatedAt()));
            return row;
        }).toList());
        account.put("nameHistory", nameHistory.findByUserIdOrderBySetAtDescIdDesc(user.getId(), Pageable.unpaged())
                .stream().map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", entry.getName());
                    row.put("setBy", String.valueOf(entry.getSetBy()).toLowerCase(java.util.Locale.ROOT));
                    row.put("setAt", text(entry.getSetAt()));
                    return row;
                }).toList());
        // Names and dates only: the secret is never stored in a form that could be handed back, and the prefix
        // is what the account page shows to tell tokens apart.
        account.put("accessTokens", tokens.findByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(token -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", token.getName());
            row.put("prefix", token.getPrefix());
            row.put("createdAt", text(token.getCreatedAt()));
            row.put("lastUsedAt", text(token.getLastUsedAt()));
            row.put("expiresAt", text(token.getExpiresAt()));
            return row;
        }).toList());
        return account;
    }

    private List<Map<String, Object>> listening(UUID userId) {
        return jdbc.query("""
                select er.slug, lp.position_seconds, lp.updated_at
                from listening_progress lp join episode_ref er on er.id = lp.episode_ref_id
                where lp.user_id = ? order by lp.updated_at desc
                """, (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("episode", rs.getString("slug"));
                    row.put("positionSeconds", rs.getInt("position_seconds"));
                    row.put("updatedAt", text(rs.getTimestamp("updated_at").toInstant()));
                    return row;
                }, userId);
    }

    private List<Map<String, Object>> notifications(UUID userId) {
        return jdbc.query("""
                select source, kind, payload::text as payload, link, created_at, read_at
                from notification where user_id = ? order by created_at desc
                """, (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("from", rs.getString("source"));
                    row.put("kind", rs.getString("kind"));
                    row.put("content", json.readTree(rs.getString("payload")));
                    row.put("link", rs.getString("link"));
                    row.put("createdAt", text(rs.getTimestamp("created_at").toInstant()));
                    java.sql.Timestamp read = rs.getTimestamp("read_at");
                    row.put("readAt", read == null ? null : text(read.toInstant()));
                    return row;
                }, userId);
    }

    /** The {@code USER}-scope documents core holds on plugins' behalf (§7.6), by plugin then key. */
    private Map<String, Map<String, Object>> userDocuments(UUID userId) {
        Map<String, Map<String, Object>> byPlugin = new LinkedHashMap<>();
        jdbc.query("""
                select plugin_id, key, value::text as value from plugin_data
                where scope_type = 'user' and scope_id = ? order by plugin_id, key
                """, rs -> {
                    byPlugin.computeIfAbsent(rs.getString("plugin_id"), id -> new LinkedHashMap<>())
                            .put(rs.getString("key"), json.readTree(rs.getString("value")));
                }, userId.toString());
        return byPlugin;
    }

    private String readme() {
        return """
                Your data from this Mosaicast site
                ==================================

                core/account.json        your account: name, role, the sign-in providers you linked, your
                                         name history and your access tokens (names and dates, never the
                                         secret)
                core/listening.json      where you stopped in each episode
                core/notifications.json  your inbox
                core/plugin-documents/   what plugins stored for you personally, one file per plugin
                plugins/<plugin>/        what each plugin handed over, in its own format
                outcome.json             what came of asking each plugin:
                                           complete       it handed its part over
                                           empty          it holds nothing on you
                                           failed         asking it went wrong; try again later
                                           outstanding    it is switched off, so it could not be asked
                                           not-supported  it cannot export; ask the site's operator

                Deine Daten von dieser Mosaicast-Seite
                ======================================

                core/account.json        dein Konto: Name, Rolle, verknüpfte Anmeldedienste, frühere Namen
                                         und deine Zugriffstoken (Name und Datum, nie das Geheimnis)
                core/listening.json      wo du in jeder Folge aufgehört hast
                core/notifications.json  deine Benachrichtigungen
                core/plugin-documents/   was Plugins persönlich für dich gespeichert haben, je Plugin eine Datei
                plugins/<plugin>/        was jedes Plugin übergeben hat, in seinem eigenen Format
                outcome.json             was beim Anfragen jedes Plugins herauskam:
                                           complete       übergeben
                                           empty          es hat nichts über dich
                                           failed         ging schief; versuch es später noch einmal
                                           outstanding    es ist ausgeschaltet und konnte nicht gefragt werden
                                           not-supported  es kann nicht exportieren; frag den Betreiber der Seite
                """;
    }

    private void putJson(ZipOutputStream zip, String path, Object value) throws IOException {
        put(zip, path, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        OutputStream out = zip;
        out.write(bytes);
        zip.closeEntry();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
