// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.ObjectMapper;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.SchemaStore;
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
import org.slf4j.MDC;
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
    private final PluginSettingsService settings;
    private final ObjectMapper objectMapper;
    private final PluginSchemaMigrator schemaMigrator;
    private final PluginBlobService blobService;
    private final dev.mosaicast.core.tag.TagService tagService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final dev.mosaicast.core.auth.UserRepository userRepository;

    /** Registrations in discovery order, keyed by id; populated once at startup. */
    private final Map<String, PluginRegistration> registrations = new LinkedHashMap<>();

    /**
     * The {@link SchemaStore} of every plugin that declared one, keyed by id — the same instance its
     * {@code register(ctx)} was handed, so the HTTP surface and the plugin's own backend read through one
     * object rather than two built from the same manifest.
     */
    private final Map<String, SchemaStoreImpl> schemaStores = new LinkedHashMap<>();

    /**
     * The site's language registry, shared by every plugin (§12.7).
     *
     * <p>One instance for all of them, unlike the per-plugin stores above: which languages a site has is a
     * property of the site, not of who is asking.
     */
    private final dev.mosaicast.plugin.api.Locales locales;
    private final dev.mosaicast.core.external.translation.TranslationService translations;

    /** The PF4J manager, kept after boot so optional extension points can be resolved on demand (§7.4). */
    private MosaicastPluginManager manager;

    public PluginLoaderService(PluginProperties properties, PluginDataService dataService,
                               FeedAccess feedAccess, PluginScheduler scheduler,
                               PluginSettingsService settings, ObjectMapper objectMapper,
                               PluginSchemaMigrator schemaMigrator, PluginBlobService blobService,
                               dev.mosaicast.core.tag.TagService tagService,
                               dev.mosaicast.plugin.api.Locales locales,
                               dev.mosaicast.core.external.translation.TranslationService translations,
                               org.springframework.jdbc.core.JdbcTemplate jdbc,
                               dev.mosaicast.core.auth.UserRepository userRepository) {
        this.properties = properties;
        this.dataService = dataService;
        this.feedAccess = feedAccess;
        this.scheduler = scheduler;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.schemaMigrator = schemaMigrator;
        this.blobService = blobService;
        this.tagService = tagService;
        this.locales = locales;
        this.translations = translations;
        this.jdbc = jdbc;
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path root = Path.of(properties.pluginsDir());
        if (!Files.isDirectory(root)) {
            log.info("Plugins directory {} does not exist; no plugins loaded", root.toAbsolutePath());
            return;
        }
        manager = new MosaicastPluginManager(root);
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
        // Tag everything logged while handling this folder with the plugin it belongs to, so the admin log can
        // filter by plugin instead of parsing ids back out of message text. The tag wraps the whole call
        // rather than the try block inside it: try-with-resources closes its resource *before* a catch runs,
        // so a rejection — the entry that matters most here — would otherwise be logged untagged.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", fallbackId)) {
            loadOneTagged(manager, folder, fallbackId);
        }
    }

    private void loadOneTagged(MosaicastPluginManager manager, Path folder, String fallbackId) {
        PluginManifest manifest = null;
        try {
            Path manifestFile = folder.resolve("plugin.json");
            if (!Files.exists(manifestFile)) {
                // Not a plugin folder — skip quietly rather than reporting a rejection.
                return;
            }
            manifest = objectMapper.readValue(manifestFile.toFile(), PluginManifest.class);
            manifest.validate();
            requireIdMatchesFolder(manifest, fallbackId);

            if (!settings.enabled(manifest.id())) {
                // Switched off by an admin: never start the backend at all. This is the half of activation
                // that a running host cannot do (§7.8) — at runtime the host can only gate its own surfaces.
                register(PluginRegistration.disabled(manifest, folder));
                log.info("Skipped plugin '{}': disabled by admin", manifest.id());
                return;
            }

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

    /**
     * Builds the context one plugin's {@code register(ctx)} receives.
     *
     * <p>A schema declaration is provisioned here, before {@code register} runs: a plugin's first act is
     * often to seed or migrate its own rows, so the tables have to exist by the time it gets the context.
     * Provisioning throws on a declaration the host cannot apply, which lands in the same catch as any
     * other load failure and disables that plugin alone (§7.8).
     */
    private PluginContextImpl buildContext(PluginManifest manifest) {
        DocStoreImpl store = new DocStoreImpl(manifest.id(), dataService);
        PluginConfigImpl config =
                new PluginConfigImpl(manifest.id(), manifest.config(), settings, objectMapper);

        Map<String, PluginSchemaValidator.Entity> entities = manifest.schemaEntities();
        SchemaStoreImpl schema = null;
        if (!entities.isEmpty()) {
            schemaMigrator.provision(manifest.id(), entities);
            schema = new SchemaStoreImpl(manifest.id(), entities, jdbc, objectMapper);
            schemaStores.put(manifest.id(), schema);
        }
        // Null unless the manifest asked for it, mirroring the schema store above: what a plugin may store
        // is decided in the manifest and nowhere else (§11).
        PluginBlobsImpl blobs = manifest.declaresBlobs() ? new PluginBlobsImpl(manifest, blobService) : null;
        // Same rule again for the shared tag vocabulary (§6.1): declared or absent, never inferred.
        TagsImpl tags = manifest.declaresTags() ? new TagsImpl(manifest, tagService) : null;
        // And once more for external services (§16). Only the manifest is consulted here: whether a provider
        // is selected is an operator's decision that changes under a running plugin, so a backend gets the
        // handle and asks `available()`, rather than being handed null and having to restart to notice.
        PluginTranslationImpl translation =
                manifest.usesExternalKind(dev.mosaicast.core.external.ExternalServiceKind.TRANSLATION)
                        ? new PluginTranslationImpl(translations) : null;
        // Null unless declared, like blobs and tags: what a plugin may touch is decided in the manifest
        // and nowhere else (§7.2, §8.8).
        dev.mosaicast.plugin.api.Users users = manifest.declaresIdentity() ? new UsersImpl(userRepository) : null;
        return new PluginContextImpl(manifest.id(), store, schema, blobs, tags, users, config, feedAccess, locales,
                translation, scheduler);
    }

    /**
     * Requires a plugin's declared id to match the folder it was found in.
     *
     * <p>The registry is keyed on {@code manifest.id()} — the plugin's own claim about who it is — and
     * {@code registrations.put} silently overwrites. Folders load in alphabetical order, so a package
     * installed as {@code zz-analytics/} declaring {@code "id": "bingo"} took over the real {@code bingo}
     * plugin's entry: its bundle was served from {@code /plugins/bingo/assets/**}, its {@code visibleTo}
     * floors gated {@code /api/plugins/bingo/data/**}, and both backends kept writing to the same
     * {@code plugin_data} rows under {@code plugin_id = 'bingo'}. One plugin wearing another's identity, with
     * a shared data namespace, and nothing said so.
     *
     * <p>The folder name is the one thing about a plugin the operator chose rather than the author, so it is
     * the right tiebreaker. This needs filesystem access to the plugins directory — defence in depth, like
     * the asset symlink check — but a mismatch is also just a mistake worth naming: an author who renames
     * their plugin and forgets the folder currently gets silence.
     */
    private static void requireIdMatchesFolder(PluginManifest manifest, String folderName) {
        if (!manifest.id().equals(folderName)) {
            throw new PluginValidationException(
                    ("manifest id '%s' does not match its folder '%s' — a plugin is identified by the folder "
                            + "it was installed into, so the two must agree (rename the folder, or fix the "
                            + "manifest id)").formatted(manifest.id(), folderName));
        }
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

    /**
     * The plugin's implementations of an optional extension point (ARCHITECTURE §7.4), e.g.
     * {@code ShareMetadataProvider} or {@code SitemapProvider}. Empty for an unknown, rejected or
     * switched-off plugin, and empty — never a throw — when PF4J cannot resolve the type, so an optional
     * contract a plugin does not implement stays optional.
     */
    public <T> List<T> extensions(Class<T> extensionPoint, String pluginId) {
        if (manager == null || active(pluginId).isEmpty()) {
            return List.of();
        }
        try {
            return manager.getExtensions(extensionPoint, pluginId);
        } catch (Exception e) {
            log.warn("Cannot resolve {} of plugin '{}': {}", extensionPoint.getSimpleName(), pluginId,
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * The registration of any discovered plugin, whatever its state — the admin surface must be able to
     * configure and re-enable a plugin that is currently disabled or rejected.
     */
    public Optional<PluginRegistration> registration(String id) {
        return Optional.ofNullable(registrations.get(id));
    }

    /** The registration for a loaded plugin by id, or empty when unknown or rejected. */
    public Optional<PluginRegistration> loaded(String id) {
        return Optional.ofNullable(registrations.get(id)).filter(PluginRegistration::isLoaded);
    }

    /**
     * The registration for a plugin that is loaded <em>and</em> currently switched on — the gate every
     * public surface uses (manifest, doc-store API, assets). Distinct from {@link #loaded}: a plugin toggled
     * off while the host runs stays {@code LOADED} until the next boot, but must stop serving immediately.
     */
    public Optional<PluginRegistration> active(String id) {
        return loaded(id).filter(r -> settings.enabled(id));
    }

    /**
     * The {@link SchemaStore} of a plugin that is loaded, switched on <em>and</em> declares a schema —
     * empty otherwise, which is the doc-store case as much as the unknown-plugin one.
     *
     * <p>Gated on {@link #active} rather than on the map alone, so switching a plugin off closes its
     * schema surface at the same instant it closes its doc surface (§7.8): the store object survives the
     * toggle, the access to it does not.
     *
     * <p>This is the object the plugin's own {@code register(ctx)} received. Handing the HTTP layer a
     * second store built from the same manifest would work today and drift the moment one of them learns
     * something the other does not.
     */
    public Optional<SchemaStoreImpl> schemaOf(String id) {
        return active(id).map(r -> schemaStores.get(r.id()));
    }

    /** Every plugin that is loaded and switched on, in discovery order. */
    public List<PluginRegistration> allActive() {
        return registrations.values().stream()
                .filter(PluginRegistration::isLoaded)
                .filter(r -> settings.enabled(r.id()))
                .toList();
    }

    /** Whether the plugin is currently switched on (an unknown or never-toggled plugin counts as on). */
    public boolean isEnabled(String id) {
        return settings.enabled(id);
    }
}
