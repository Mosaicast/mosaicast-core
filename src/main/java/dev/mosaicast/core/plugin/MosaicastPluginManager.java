// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.pf4j.BasePluginLoader;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ExtensionFactory;
import org.pf4j.PluginClasspath;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginLoader;
import org.pf4j.SingletonExtensionFactory;

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
     * One instance per extension class, reused across every extension point it implements.
     *
     * <p>PF4J's default factory constructs a <strong>fresh instance per lookup</strong>. A plugin class
     * implementing {@code PluginBackend}, {@code ShareMetadataProvider} and {@code SitemapProvider} — which
     * §7.4 explicitly invites, and the sample plugin does — therefore became three unrelated objects: the
     * host called {@code register(ctx)} on the first, and asked the other two for share metadata and sitemap
     * entries. Their context field had never been set.
     *
     * <p>The visible symptom was a {@code sitemap.xml} silently missing every plugin URL, and OG tags for
     * {@code /p/&lt;id&gt;/…} silently falling back to site-level ones. A plugin that dereferenced the field
     * instead of null-checking it got a {@link NullPointerException} from a call it never made itself.
     *
     * <p>The sample plugin worked around it by making its context {@code static}, and documented the trap at
     * length. That is a workaround every plugin author would have to rediscover the same way — by shipping
     * something that quietly does nothing — and nothing in the SDK contract says an extension point runs on
     * a different object than {@code register}. Fixed here instead, where it is one line and applies to
     * every plugin: PF4J's own {@link SingletonExtensionFactory} caches by class, so the object the host
     * registered is the object it later asks.
     */
    @Override
    protected ExtensionFactory createExtensionFactory() {
        return new SingletonExtensionFactory(this);
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
