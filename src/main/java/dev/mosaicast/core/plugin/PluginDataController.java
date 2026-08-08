// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.PagedResponse;
import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The generic, per-plugin doc-store HTTP surface the frontend {@code ctx.api} targets (ARCHITECTURE §7.6).
 * Mirrors {@link dev.mosaicast.plugin.api.DocStore} one-to-one — get one, list, put, delete — over the
 * plugin's own hard-scoped {@code plugin_data}. Reads are gated by the plugin's {@code visibleTo} floor and
 * writes by the mapped role ({@link PluginAccessPolicy}); an unknown/rejected plugin, an unknown scope type
 * or a scope naming something that does not exist is a 404. There are no plugin-authored routes — this is the
 * whole surface.
 *
 * <p><strong>What this does not protect, stated so nobody reads the paragraph above as more than it is.</strong>
 * Authorization here is per <em>plugin</em>, not per <em>document</em>. Nothing binds a document to the user
 * who wrote it, so any caller who clears the plugin's role floor can read, overwrite or delete any key in any
 * scope — including keys another user's session created. The SDK's own guidance makes that concrete: with no
 * user-level scope available, {@code DocStore}'s javadoc tells plugin authors to model per-user data inside
 * the key (<code>mark:&lt;userId&gt;:cell</code>), and the host has never checked that {@code userId} against
 * the caller. Scope ids are public slugs, and listing takes a prefix, so nothing has to be guessed either.
 *
 * <p>Closing it properly needs a partition the client cannot address — a host-owned user scope, which is a
 * plugin-contract change rather than something this controller can do alone (§7.3 fixes the scope tuple at
 * site/feed/season/episode). Until then: a plugin storing anything one user should not be able to reach for
 * another is relying on a guarantee the host does not make.
 */
@RestController
public class PluginDataController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PluginLoaderService plugins;
    private final PluginDataService data;
    private final FeedAccessImpl scopes;

    public PluginDataController(PluginLoaderService plugins, PluginDataService data, FeedAccessImpl scopes) {
        this.plugins = plugins;
        this.data = data;
        this.scopes = scopes;
    }

    /** One document, or 404 when absent (the frontend relies on the 404). */
    @GetMapping("/api/plugins/{id}/data/{scopeType}/{scopeId}/{key}")
    public JsonNode get(@PathVariable String id, @PathVariable String scopeType,
                        @PathVariable String scopeId, @PathVariable String key, Authentication authentication) {
        // The plugin first: an unknown or switched-off plugin has no data surface at all, and should
        // not have scope errors answered on its behalf.
        PluginManifest manifest = manifestOf(id);
        DataScope scope = scope(scopeType, scopeId, authentication);
        requireReadable(manifest, scope, authentication);
        return data.getRaw(id, scope, key)
                .orElseThrow(() -> new NotFoundException("No document: " + key));
    }

    /** Paginated list of documents in a scope, optionally filtered by key prefix. */
    @GetMapping("/api/plugins/{id}/data/{scopeType}/{scopeId}")
    public PagedResponse<DocEntry> list(@PathVariable String id, @PathVariable String scopeType,
                                        @PathVariable String scopeId,
                                        @RequestParam(defaultValue = "") String prefix,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        Authentication authentication) {
        // The plugin first: an unknown or switched-off plugin has no data surface at all, and should
        // not have scope errors answered on its behalf.
        PluginManifest manifest = manifestOf(id);
        DataScope scope = scope(scopeType, scopeId, authentication);
        requireReadable(manifest, scope, authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        return PagedResponse.of(data.queryPage(id, scope, prefix, pageable), e -> e);
    }

    /** Upserts a document (last-write-wins). Body is the raw JSON value. */
    @PutMapping("/api/plugins/{id}/data/{scopeType}/{scopeId}/{key}")
    public ResponseEntity<Void> put(@PathVariable String id, @PathVariable String scopeType,
                                    @PathVariable String scopeId, @PathVariable String key,
                                    @RequestBody JsonNode body, Authentication authentication) {
        // The plugin first: an unknown or switched-off plugin has no data surface at all, and should
        // not have scope errors answered on its behalf.
        PluginManifest manifest = manifestOf(id);
        DataScope scope = scope(scopeType, scopeId, authentication);
        requireWritable(manifest, scope, authentication);
        data.putRaw(id, scope, key, body);
        return ResponseEntity.noContent().build();
    }

    /** Removes a document; idempotent (204 whether or not one existed). */
    @DeleteMapping("/api/plugins/{id}/data/{scopeType}/{scopeId}/{key}")
    public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String scopeType,
                                       @PathVariable String scopeId, @PathVariable String key,
                                       Authentication authentication) {
        // The plugin first: an unknown or switched-off plugin has no data surface at all, and should
        // not have scope errors answered on its behalf.
        PluginManifest manifest = manifestOf(id);
        DataScope scope = scope(scopeType, scopeId, authentication);
        requireWritable(manifest, scope, authentication);
        data.delete(id, scope, key);
        return ResponseEntity.noContent().build();
    }

    /**
     * Checks the plugin's declared read floor, or throws 404 / 403.
     *
     * <p>{@code USER} is exempt (§7.6). A floor describes who may reach the <em>shared</em> scopes, where one
     * caller's data is visible to others; a user partition has exactly one reader by construction, and no
     * floor either opens someone else's or should stand between a caller and their own. Applying it here
     * would mean a plugin declaring {@code readableBy: "podcaster"} hid a fan's own saved state from them.
     */
    private void requireReadable(PluginManifest manifest, DataScope scope, Authentication authentication) {
        if (scope.type() == ScopeType.USER) {
            return;
        }
        if (!PluginAccessPolicy.canRead(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to read plugin data: " + manifest.id());
        }
    }

    /**
     * Checks the plugin's declared write floor, or throws 404 / 403.
     *
     * <p>{@code USER} is exempt for the same reason as reads, and the consequence is the one the scope exists
     * for: a fan marks their own bingo card under a plugin that declares {@code writableBy: "podcaster"} for
     * its shared scopes. Gating it would force such a plugin to declare {@code writableBy: "fan"} — opening
     * its shared scopes to fan writes to make its own per-user feature work, which is exactly the coupling
     * the declared floors removed on the read side.
     */
    private void requireWritable(PluginManifest manifest, DataScope scope, Authentication authentication) {
        if (scope.type() == ScopeType.USER) {
            return;
        }
        if (!PluginAccessPolicy.canWrite(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to write plugin data: " + manifest.id());
        }
    }

    /**
     * The manifest of a plugin that is loaded and switched on. A disabled plugin is indistinguishable from an
     * absent one here on purpose (§7.8): switching a plugin off must close its data surface immediately, not
     * at the next restart.
     */
    private PluginManifest manifestOf(String id) {
        return plugins.active(id)
                .map(PluginRegistration::manifest)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
    }

    /**
     * Parses and validates a scope from the request path, resolving {@code user/me} to the caller.
     *
     * <p>A scope naming nothing real is a 404, not a fresh partition. Same status as an unknown scope type,
     * deliberately: "this address does not exist" is one answer, and splitting it would tell a caller which
     * feed slugs and episode slugs are real — which the public API already tells them anyway, so the value is
     * in the consistency rather than in the secrecy.
     *
     * <p><strong>{@code USER} is the one scope whose id the client does not get to choose.</strong> The path
     * says {@code me} and the id that reaches the store is the session's user, substituted here. That is what
     * makes another user's partition unaddressable rather than merely forbidden: there is no request that
     * names it.
     */
    private DataScope scope(String scopeType, String scopeId, Authentication authentication) {
        ScopeType type;
        try {
            type = ScopeType.valueOf(scopeType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope type: " + scopeType);
        }
        if (type == ScopeType.USER) {
            return DataScope.ofUser(userPartition(scopeId, authentication));
        }
        Scope scope = new Scope(type, scopeId);
        if (!scopes.exists(scope)) {
            throw new NotFoundException("Unknown scope: " + scopeType + "/" + scopeId);
        }
        return DataScope.of(scope);
    }

    /**
     * Resolves the caller's own partition.
     *
     * <p>Anything but the literal sentinel is a <strong>400</strong>, never a silent substitution. Quietly
     * treating {@code user/<someone-else>} as {@code user/me} would let a plugin ship code that reads as
     * though it addresses a specific person and behaves as though it does not — the kind of bug that surfaces
     * years later as "why is everyone seeing the same board". Refusing it says so at the first request.
     *
     * <p>Anonymous is a <strong>401</strong> regardless of the plugin's declared floors: there is no session,
     * so there is no partition to resolve. And neither floor applies here in either direction (§7.6) — a
     * {@code readableBy} cannot make somebody else's partition readable, and neither floor stands between a
     * caller and their own, so a fan marking their own card works under a plugin declaring
     * {@code writableBy: "podcaster"} for its shared scopes.
     */
    private UUID userPartition(String scopeId, Authentication authentication) {
        if (!Scope.SELF_ID.equals(scopeId)) {
            throw new IllegalArgumentException(
                    "USER scope is addressed as '" + Scope.SELF_ID
                            + "'; the server resolves it to the calling user.");
        }
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException(
                        "The user scope needs a signed-in caller."));
    }
}
