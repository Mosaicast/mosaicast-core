// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.pf4j.BasePluginLoader;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginClasspath;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginLoader;

/**
 * A PF4J {@link DefaultPluginManager} adapted to Mosaicast's plugin layout (ARCHITECTURE §7.1): each plugin
 * is a folder holding a {@code plugin.json} manifest and a root-level backend JAR (which carries the PF4J
 * {@code extensions.idx}). Two overrides make PF4J load that layout:
 *
 * <ul>
 *   <li>{@link #createPluginDescriptorFinder()} reads our {@code plugin.json} instead of a PF4J
 *       {@code plugin.properties}, mapping it to a descriptor whose plugin class is the framework's no-op
 *       {@code org.pf4j.Plugin} (our plugins declare no {@code Plugin} subclass — their code is
 *       {@code @Extension} classes).</li>
 *   <li>{@link #createPluginLoader()} adds the plugin folder root as a jars directory so the backend JAR
 *       (and its {@code extensions.idx}) lands on the plugin classloader.</li>
 * </ul>
 *
 * <p>Plugins declare {@code plugin-api} as {@code provided}/{@code compileOnly}, so PF4J's default
 * plugin-first strategy resolves {@code dev.mosaicast.plugin.api.*} up to the host classloader — the same
 * {@code Class} objects the host uses, so {@code getExtensions(PluginBackend.class, …)} casts cleanly (§7.1).
 */
public class MosaicastPluginManager extends DefaultPluginManager {

    public MosaicastPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    @Override
    protected PluginDescriptorFinder createPluginDescriptorFinder() {
        return new ManifestDescriptorFinder();
    }

    @Override
    protected PluginLoader createPluginLoader() {
        // "" resolves to the plugin folder root, where the backend JAR sits.
        PluginClasspath classpath = new PluginClasspath().addClassesDirectories("classes").addJarsDirectories("lib", "");
        return new BasePluginLoader(this, classpath);
    }

    /**
     * Reads {@code plugin.json} into a minimal PF4J descriptor. Self-contained (its own {@link ObjectMapper})
     * so it is safe to construct during PF4J's initialization, before subclass fields are assigned.
     */
    private static final class ManifestDescriptorFinder implements PluginDescriptorFinder {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public boolean isApplicable(Path pluginPath) {
            return Files.isDirectory(pluginPath) && Files.exists(pluginPath.resolve("plugin.json"));
        }

        @Override
        public PluginDescriptor find(Path pluginPath) {
            try {
                PluginManifest manifest =
                        objectMapper.readValue(pluginPath.resolve("plugin.json").toFile(), PluginManifest.class);
                return new DefaultPluginDescriptor(
                        manifest.id(),                       // pluginId
                        manifest.name(),                     // description
                        "org.pf4j.Plugin",                   // pluginClass — framework no-op
                        manifest.version() == null ? "0.0.0" : manifest.version(),
                        "*",                                 // requires — accept any system version
                        "",                                  // provider
                        "");                                 // license
            } catch (Exception e) {
                throw new PluginValidationException("cannot read plugin.json: " + e.getMessage());
            }
        }
    }
}
