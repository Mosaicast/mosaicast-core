// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Role;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the shell's navigation menu (ARCHITECTURE §7.3) from what plugins declare and what admins decided.
 *
 * <p>Three inputs, one ordered answer: the manifests' {@code nav} entries, the admin overrides, and the
 * caller's role. Resolution happens <strong>here rather than in the shell</strong> because the host decides
 * access — shipping every entry plus its role floor to the browser and filtering there would put a
 * podcaster-only entrance in the page source of an anonymous visitor.
 *
 * <p>Only <em>active</em> plugins contribute, so a plugin an admin switched off leaves the menu with its
 * pages, the same way it leaves the public manifest.
 */
@Service
public class PluginNavService {

    private final PluginLoaderService plugins;
    private final PluginNavOverrideRepository overrides;

    public PluginNavService(PluginLoaderService plugins, PluginNavOverrideRepository overrides) {
        this.plugins = plugins;
        this.overrides = overrides;
    }

    /** One entry as the shell renders it. */
    public record NavItem(String pluginId, String path, String href, String label, String icon) {
    }

    /** One entry as the admin edits it — every declared entry, whether or not it currently shows. */
    public record AdminNavItem(String pluginId, String pluginName, String path, String href, String label,
                               String icon, String visibleTo, boolean enabled, int order) {
    }

    /** An admin's decision about one entry. */
    public record NavDecision(String pluginId, String path, boolean enabled, int order) {
    }

    /**
     * The entries this caller may see, enabled, in order.
     *
     * @param role the caller's role, or empty for an anonymous visitor
     */
    @Transactional(readOnly = true)
    public List<NavItem> visibleTo(Optional<Role> role) {
        int rank = PluginAccessPolicy.rankOf(role);
        return resolve().stream()
                .filter(Resolved::enabled)
                .filter(entry -> rank >= floorOf(entry.entry().visibleTo()))
                .map(entry -> new NavItem(entry.pluginId(), entry.path(), entry.href(), entry.entry().label(),
                        entry.entry().icon()))
                .toList();
    }

    /** Every declared entry with the decision currently in force, in order. */
    @Transactional(readOnly = true)
    public List<AdminNavItem> adminList() {
        return resolve().stream()
                .map(entry -> new AdminNavItem(entry.pluginId(), entry.pluginName(), entry.path(), entry.href(),
                        entry.entry().label(), entry.entry().icon(), entry.entry().visibleTo(), entry.enabled(),
                        entry.order()))
                .toList();
    }

    /**
     * Applies a whole set of admin decisions.
     *
     * <p>Decisions for entries nobody declares are accepted and stored rather than refused: a plugin may be
     * temporarily disabled or mid-upgrade when the form is saved, and losing an admin's ordering because the
     * plugin happened to be off would be worse than keeping a row that currently resolves to nothing. Such a
     * row is inert until its entry comes back.
     */
    @Transactional
    public void apply(List<NavDecision> decisions) {
        Map<PluginNavOverrideKey, PluginNavOverride> existing = new HashMap<>();
        overrides.findAll().forEach(row -> existing.put(row.getId(), row));

        List<PluginNavOverride> toSave = new ArrayList<>();
        for (NavDecision decision : decisions) {
            String path = PluginManifest.normalizeNavPath(decision.path());
            PluginNavOverrideKey key = new PluginNavOverrideKey(decision.pluginId(), path);
            PluginNavOverride row = existing.get(key);
            if (row == null) {
                toSave.add(new PluginNavOverride(decision.pluginId(), path, decision.enabled(), decision.order()));
            } else {
                row.update(decision.enabled(), decision.order());
                toSave.add(row);
            }
        }
        overrides.saveAll(toSave);
    }

    // ---- resolution ----

    /** One declared entry joined to the decision in force for it. */
    private record Resolved(String pluginId, String pluginName, String path, String href, PluginManifest.NavEntry entry,
                            boolean enabled, int order) {
    }

    /**
     * Every entry every active plugin declares, joined to its override, ordered.
     *
     * <p>The natural order is each plugin's own declaration order, plugins alphabetical — an author writes
     * their entrances in the order they mean, and no manifest integer is needed to say so. An override
     * replaces that position outright; entries with no override keep theirs. Ties break on plugin id then
     * path, the same deterministic rule slots use, so the menu can never flicker between two orders.
     */
    private List<Resolved> resolve() {
        Map<PluginNavOverrideKey, PluginNavOverride> decisions = new HashMap<>();
        overrides.findAll().forEach(row -> decisions.put(row.getId(), row));

        List<Resolved> resolved = new ArrayList<>();
        int ordinal = 0;
        for (PluginRegistration registration : plugins.allActive()) {
            PluginManifest manifest = registration.manifest();
            for (PluginManifest.NavEntry entry : entriesOf(manifest)) {
                String path = entry.normalizedPath();
                PluginNavOverride decision = decisions.get(new PluginNavOverrideKey(manifest.id(), path));
                resolved.add(new Resolved(
                        manifest.id(),
                        manifest.name() == null ? manifest.id() : manifest.name(),
                        path,
                        href(manifest.id(), path),
                        entry,
                        decision == null || decision.isEnabled(),
                        decision == null ? ordinal : decision.getSortOrder()));
                ordinal++;
            }
        }
        resolved.sort(Comparator.comparingInt(Resolved::order)
                .thenComparing(Resolved::pluginId)
                .thenComparing(Resolved::path));
        return resolved;
    }

    /**
     * What a plugin offers the menu: what it declared, or one default entry if it has a page and said
     * nothing.
     *
     * <p>The default is what makes this useful without asking every existing plugin to re-release — a page
     * plugin is reachable the moment the host knows how to link to it. A plugin with no page contributes
     * nothing; {@code validate()} has already refused the contradictory case of entries without a page.
     */
    private static List<PluginManifest.NavEntry> entriesOf(PluginManifest manifest) {
        if (!manifest.declaresPage()) {
            return List.of();
        }
        List<PluginManifest.NavEntry> declared = manifest.navOrEmpty();
        if (!declared.isEmpty()) {
            return declared;
        }
        String label = manifest.name() == null || manifest.name().isBlank() ? manifest.id() : manifest.name();
        return List.of(new PluginManifest.NavEntry("", label, null, null));
    }

    private static String href(String pluginId, String path) {
        return path.isEmpty() ? "/p/" + pluginId : "/p/" + pluginId + "/" + path;
    }

    /**
     * The rank a caller needs to see an entry — the shared {@code visibleTo} mapping, which slots now use
     * too. It still differs from the doc-store floors, which fail open on an unrecognised value; that
     * distinction is documented where the mapping lives.
     */
    private static int floorOf(String visibleTo) {
        return PluginAccessPolicy.visibilityFloorOf(visibleTo);
    }
}
