// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.PagedResponse;
import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.SchemaStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only HTTP surface over a plugin's declared relational entities (ARCHITECTURE §7.6) — what
 * {@code ctx.schema} targets, the counterpart of {@link PluginDataController} for a plugin that declares
 * {@code storage.schema} instead of the doc store.
 *
 * <p>The schema provider used to be reachable only from a plugin's Java backend. Provisioning without
 * access is half a feature: the full-text index the platform builds exists for a search box, and the search
 * box is in the browser. This closes that, and nothing more — it maps one-to-one onto
 * {@link SchemaStore}'s four read methods.
 *
 * <p><strong>Reads only, and that is a contract decision rather than a milestone.</strong> A v1 plugin
 * authors no HTTP routes ({@code PluginContext} is {@code store/schema/config/feeds/logger/onSchedule}), so
 * there is no request-time hook where plugin code could validate a write: nowhere to enforce slug
 * uniqueness, append a revision atomically, or reject malformed markdown. Exposing writes here would hand
 * clients direct row access with no plugin code in the path — worse than the doc store's position, not
 * better. The plugin's backend stays the only writer of relational truth; a frontend that must write puts a
 * document in the doc store and the backend ingests it on its schedule. The cost is that such a write is
 * eventually consistent, which is a known consequence of the v1 contract.
 *
 * <p><strong>Access is the doc surface's rule, minus the scopes.</strong> Schema tables are per plugin, not
 * per scope — there is no scope in these paths, so there is no {@code USER}-scope exemption to make and
 * nothing to resolve. What is left is the flat {@code data.readableBy} floor, which is why this cannot
 * simply call {@link PluginDataController}'s check: that one takes a {@code DataScope} in order to exempt
 * one. A plugin that already declares a floor is already covered here; one setting governs both surfaces.
 *
 * <p><strong>Injection is not defended against here, it is unsayable.</strong> {@link SchemaStoreImpl}
 * resolves every entity and field name against the plugin's own manifest and builds the statement itself,
 * binding every value as a JDBC parameter. This layer's only new job is parsing a {@link Criteria} out of
 * query parameters ({@link SchemaQueryParams}) and making sure a name the manifest does not declare comes
 * back as a 400 or a 404 rather than a 500.
 */
@RestController
public class PluginSchemaController {

    private final PluginLoaderService plugins;

    public PluginSchemaController(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    /** How many rows match, for a client paging or showing a result count. */
    public record CountView(long count) {
    }

    /**
     * One page of rows of a declared entity.
     *
     * <p>Filtering and ordering come from the repeated {@code where} ({@code field:op:value}) and
     * {@code orderBy} ({@code field:asc|desc}) parameters, read off the request by {@link #terms} and
     * parsed by {@link SchemaQueryParams}.
     */
    @GetMapping("/api/plugins/{id}/schema/{entity}")
    public PagedResponse<Map<String, Object>> select(@PathVariable String id, @PathVariable String entity,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size,
                                                     HttpServletRequest request,
                                                     Authentication authentication) {
        // The plugin first, then the entity, then access — an unknown or switched-off plugin has no schema
        // surface at all, and should not have entity errors answered on its behalf.
        PluginManifest manifest = manifestOf(id);
        PluginSchemaValidator.Entity resolved = entityOf(manifest, entity);
        requireReadable(manifest, authentication);

        Criteria criteria = SchemaQueryParams.parse(resolved, terms(request, "where"), terms(request, "orderBy"));
        return pageOf(storeOf(id), entity, criteria, page, size);
    }

    /**
     * Full-text search over a field declared {@code :fulltext}, on the index the platform provisioned.
     *
     * <p>An empty {@code q} matches nothing rather than everything — the SDK contract, and the answer a
     * search box wants while the user has typed nothing. A field that is not {@code :fulltext} is a 400,
     * thrown by the store itself, so the rule is stated in exactly one place.
     */
    @GetMapping("/api/plugins/{id}/schema/{entity}/search")
    public PagedResponse<Map<String, Object>> search(@PathVariable String id, @PathVariable String entity,
                                                     @RequestParam String field,
                                                     @RequestParam(defaultValue = "") String q,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size,
                                                     HttpServletRequest request,
                                                     Authentication authentication) {
        PluginManifest manifest = manifestOf(id);
        PluginSchemaValidator.Entity resolved = entityOf(manifest, entity);
        requireReadable(manifest, authentication);

        SchemaStoreImpl store = storeOf(id);
        Criteria criteria = SchemaQueryParams.parse(resolved, terms(request, "where"), terms(request, "orderBy"));
        int index = PagedResponse.page(page);
        int limit = PagedResponse.size(size);
        List<Map<String, Object>> rows = rows(
                store.search(entity, field, q, paged(criteria, index, limit), Map.class));

        // The total needs the same match clause the rows were selected with, which the SDK's count() does
        // not take — hence the core-only searchCount. Deriving it from the page's own length instead would
        // tell a client on page 0 that there is no page 1.
        return PagedResponse.of(rows, index, limit, store.searchCount(entity, field, q, criteria));
    }

    /** How many rows match a filter, without fetching them. Ordering and paging are ignored. */
    @GetMapping("/api/plugins/{id}/schema/{entity}/count")
    public CountView count(@PathVariable String id, @PathVariable String entity,
                           HttpServletRequest request, Authentication authentication) {
        PluginManifest manifest = manifestOf(id);
        PluginSchemaValidator.Entity resolved = entityOf(manifest, entity);
        requireReadable(manifest, authentication);

        return new CountView(storeOf(id).count(
                entity, SchemaQueryParams.parse(resolved, terms(request, "where"), null)));
    }

    /** One row by its platform-assigned id, or 404 when absent (the frontend relies on the 404). */
    @GetMapping("/api/plugins/{id}/schema/{entity}/{rowId}")
    public Map<String, Object> find(@PathVariable String id, @PathVariable String entity,
                                    @PathVariable long rowId, Authentication authentication) {
        PluginManifest manifest = manifestOf(id);
        entityOf(manifest, entity);
        requireReadable(manifest, authentication);

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) storeOf(id).find(entity, rowId, Map.class)
                .orElseThrow(() -> new NotFoundException(
                        "No %s with id %d in plugin '%s'".formatted(entity, rowId, id)));
        return row;
    }

    /**
     * The repeated values of one query parameter, exactly as the client wrote them.
     *
     * <p>Read off the request rather than bound with {@code @RequestParam List<String>}, because Spring
     * splits a comma-containing value into several elements when it binds a collection: {@code
     * where=slug:in:kraken,seeded} would arrive as {@code ["slug:in:kraken", "seeded"]} and the second half
     * would be refused as a malformed term. That would break every {@code in} list — the one place the
     * grammar uses a comma — and quietly truncate any other value containing one.
     */
    private static List<String> terms(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    /** Runs the query and its count, and wraps both in the envelope the doc surface uses. */
    private PagedResponse<Map<String, Object>> pageOf(SchemaStoreImpl store, String entity, Criteria criteria,
                                                      int page, int size) {
        int index = PagedResponse.page(page);
        int limit = PagedResponse.size(size);
        List<Map<String, Object>> rows = rows(store.select(entity, paged(criteria, index, limit), Map.class));
        // count() ignores limit/offset by contract, so the same criteria object gives the unpaged total.
        return PagedResponse.of(rows, index, limit, store.count(entity, criteria));
    }

    private static Criteria paged(Criteria criteria, int page, int size) {
        Criteria limited = criteria.limit(size);
        return page > 0 ? limited.offset(page * size) : limited;
    }

    /**
     * Rows as JSON objects.
     *
     * <p>{@code Map} rather than a record: the columns are whatever this plugin declared, so there is no
     * type to name here. {@link SchemaStoreImpl} has already normalized the JDBC values, so a
     * {@code timestamptz} serializes as an ISO-8601 instant rather than as epoch milliseconds.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(List<? extends Map> raw) {
        return (List<Map<String, Object>>) raw;
    }

    /**
     * Checks the plugin's declared read floor, or throws 403.
     *
     * <p>No {@code USER} exemption to make: that exists on the doc surface because a user partition has
     * exactly one reader by construction, and a schema table is per plugin, shared by everyone the floor
     * admits. So the floor is the whole rule here.
     */
    private void requireReadable(PluginManifest manifest, Authentication authentication) {
        if (!PluginAccessPolicy.canRead(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to read plugin data: " + manifest.id());
        }
    }

    /**
     * The manifest of a plugin that is loaded and switched on. A disabled plugin is indistinguishable from
     * an absent one here on purpose (§7.8), exactly as on the doc surface.
     */
    private PluginManifest manifestOf(String id) {
        return plugins.active(id)
                .map(PluginRegistration::manifest)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
    }

    /**
     * The store of a plugin that declares a schema.
     *
     * <p>A doc-store plugin answers <strong>404</strong>, not 400: it has no {@code schema/…} address at
     * all, which is the same answer as for a plugin that does not exist. Called after
     * {@link #entityOf}, which has already refused an undeclared entity — a plugin with no schema declares
     * no entities, so the two agree.
     */
    private SchemaStoreImpl storeOf(String id) {
        return plugins.schemaOf(id)
                .orElseThrow(() -> new NotFoundException("Plugin '" + id + "' declares no schema"));
    }

    /**
     * The declared entity, or 404.
     *
     * <p>Checked here rather than left to the store, which throws {@link IllegalArgumentException} — a 400.
     * That is right for a plugin's own Java code, where an undeclared name means the manifest and the code
     * disagree; over HTTP the entity is a <em>path segment</em>, and a path segment that names nothing is
     * an address that does not exist. Field names stay 400: they are parameters, not addresses.
     */
    private static PluginSchemaValidator.Entity entityOf(PluginManifest manifest, String entity) {
        PluginSchemaValidator.Entity resolved = manifest.schemaEntities().get(entity);
        if (resolved == null) {
            throw new NotFoundException(
                    "Plugin '%s' declares no entity '%s'".formatted(manifest.id(), entity));
        }
        return resolved;
    }
}
