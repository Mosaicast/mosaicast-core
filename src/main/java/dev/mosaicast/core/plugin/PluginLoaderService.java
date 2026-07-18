// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.PlatformApi;
import dev.mosaicast.plugin.api.PluginBackend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.pf4j.PluginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Loads plugins from {@code MOSAICAST_PLUGINS_DIR} once at startup (ARCHITECTURE §7.1). Each plugin folder is
 * discovered, its {@code plugin.json} parsed and validated, its backend JAR loaded via PF4J, and every
 * {@link PluginBackend} extension registered with a host-built {@link PluginContextImpl}.
 *
 * <p><strong>Failure isolation (§7.8):</strong> every step for a plugin is wrapped so a bad manifest, an
 * incompatible {@code platformApi}, a declared schema, or a thrown exception disables only that plugin —
 * recorded as {@link PluginRegistration.Status#REJECTED} with a reason — while the host keeps booting. No
 * plugin can ever crash the host.
 */
@Service
@Order(0)
public class PluginLoaderService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginLoaderService.class);

    private final PluginProperties properties;
    private final PluginDataService dataService;
    private final FeedAccess feedAccess;
    private final PluginScheduler scheduler;
    private final ObjectMapper objectMapper;

    /** Registrations in discovery order, keyed by id; populated once at startup. */
    private final Map<String, PluginRegistration> registrations = new LinkedHashMap<>();

    public PluginLoaderService(PluginProperties properties, PluginDataService dataService,
                               FeedAccess feedAccess, PluginScheduler scheduler, ObjectMapper objectMapper) {
        this.properties = properties;
        this.dataService = dataService;
        this.feedAccess = feedAccess;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path root = Path.of(properties.pluginsDir());
        if (!Files.isDirectory(root)) {
            log.info("Plugins directory {} does not exist; no plugins loaded", root.toAbsolutePath());
            return;
        }
        MosaicastPluginManager manager = new MosaicastPluginManager(root);
        manager.setSystemVersion(PlatformApi.VERSION);
        List<Path> folders = pluginFolders(root);
        for (Path folder : folders) {
            loadOne(manager, folder);
        }
        long loaded = registrations.values().stream().filter(PluginRegistration::isLoaded).count();
        log.info("Plugin loading complete: {} loaded, {} rejected, from {}",
                loaded, registrations.size() - loaded, root.toAbsolutePath());
    }

    private void loadOne(MosaicastPluginManager manager, Path folder) {
        String fallbackId = folder.getFileName().toString();
        PluginManifest manifest = null;
        try {
            Path manifestFile = folder.resolve("plugin.json");
            if (!Files.exists(manifestFile)) {
                // Not a plugin folder — skip quietly rather than reporting a rejection.
                return;
            }
            manifest = objectMapper.readValue(manifestFile.toFile(), PluginManifest.class);
            manifest.validate();

            String pluginId = manager.loadPlugin(folder);
            PluginState state = manager.startPlugin(pluginId);
            if (state != PluginState.STARTED) {
                throw new PluginValidationException("plugin did not start (state " + state + ")");
            }
            List<PluginBackend> backends = manager.getExtensions(PluginBackend.class, pluginId);
            PluginContextImpl ctx = buildContext(manifest);
            for (PluginBackend backend : backends) {
                backend.register(ctx);
            }
            register(PluginRegistration.loaded(manifest, folder));
            log.info("Loaded plugin '{}' ({} backend(s))", pluginId, backends.size());
        } catch (Exception e) {
            String id = manifest != null && manifest.id() != null ? manifest.id() : fallbackId;
            register(PluginRegistration.rejected(id, e.getMessage(), manifest, folder));
            log.warn("Rejected plugin '{}': {}", id, e.getMessage());
        }
    }

    private PluginContextImpl buildContext(PluginManifest manifest) {
        DocStoreImpl store = new DocStoreImpl(manifest.id(), dataService);
        PluginConfigImpl config = new PluginConfigImpl(manifest.config(), objectMapper);
        return new PluginContextImpl(manifest.id(), store, config, feedAccess, scheduler);
    }

    private void register(PluginRegistration registration) {
        registrations.put(registration.id(), registration);
    }

    private static List<Path> pluginFolders(Path root) {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot list plugins directory {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    /** All discovered plugins with their load state, in discovery order (for the admin surface). */
    public List<PluginRegistration> all() {
        return new ArrayList<>(registrations.values());
    }

    /** The registration for a loaded plugin by id, or empty when unknown or rejected. */
    public Optional<PluginRegistration> loaded(String id) {
        return Optional.ofNullable(registrations.get(id)).filter(PluginRegistration::isLoaded);
    }
}
