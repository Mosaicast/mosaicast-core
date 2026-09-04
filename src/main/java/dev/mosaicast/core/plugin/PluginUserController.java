// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.UserRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser half of {@code ctx.users} (ARCHITECTURE §8.8) — the frontend twin of the backend's
 * {@link dev.mosaicast.plugin.api.Users}.
 *
 * <p>A plugin that aggregates across users holds UUIDs and nothing else, and a leaderboard is drawn in a
 * browser. Without this the plugin's backend would have to resolve names and hand them to its own frontend
 * through its doc store — which is precisely the copying §8.8 forbids, since a name copied there survives
 * the rename meant to shed it and the erasure meant to end it.
 *
 * <p><strong>Gated by the manifest, not by the caller's role.</strong> A plugin with no {@code identity}
 * block gets a 404, the same shape {@code blobs} and {@code tags} already have (§7.2). There is no role
 * floor because there is nothing here a visitor could not already see: the names and pictures on a
 * leaderboard are shown to whoever can see the leaderboard.
 *
 * <p><strong>It resolves; it does not enumerate.</strong> Ids only, no listing, and no way to ask "who
 * exists" — a plugin can ask only about ids it already came by through its own scope.
 */
@RestController
public class PluginUserController {

    private final PluginLoaderService plugins;
    private final UsersImpl users;

    public PluginUserController(PluginLoaderService plugins, UserRepository userRepository) {
        this.plugins = plugins;
        this.users = new UsersImpl(userRepository);
    }

    /**
     * Resolves user ids to people.
     *
     * <p>Unresolvable ids are <strong>absent rather than redacted</strong>: unknown, erased and
     * pseudonymised (§12.8) all produce the same missing row, so the answer cannot be used to work out
     * which of the three a given id is. The result is therefore not aligned with the request and may be
     * shorter than it — callers match on {@code id}.
     *
     * <p>An unparseable id is skipped rather than a 400, for the same reason: a caller that can tell
     * "malformed" from "no such user" learns something about ids it guessed.
     *
     * @param id   the calling plugin
     * @param ids  comma-separated user UUIDs; beyond {@link UsersImpl#MAX_IDS} the rest are ignored
     */
    @GetMapping("/api/plugins/{id}/users")
    public List<UserRef> resolve(@PathVariable String id, @RequestParam(defaultValue = "") String ids) {
        requireIdentityPlugin(id);
        return users.resolve(parseIds(ids));
    }

    /** The requested ids: parseable ones only, de-duplicated by the resolver, clamped there too. */
    private static List<UUID> parseIds(String ids) {
        List<UUID> asked = new ArrayList<>();
        if (ids.isBlank()) {
            return asked;
        }
        for (String raw : ids.split(",")) {
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            try {
                asked.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // Not a user id, so not a user. Skipped rather than refused — see the method comment.
            }
        }
        return asked;
    }

    /**
     * A loaded, switched-on plugin that declared {@code identity} — 404 otherwise.
     *
     * <p>One 404 for both conditions on purpose. "This plugin is off" and "this plugin never asked for the
     * user directory" are the same answer to a caller who should not have reached here either way, and
     * telling them apart would let a page probe an install's manifest set.
     */
    private void requireIdentityPlugin(String id) {
        PluginRegistration registration = plugins.active(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        if (!registration.manifest().declaresIdentity()) {
            throw new NotFoundException("Unknown plugin: " + id);
        }
    }
}
