// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.MeView;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.plugin.api.UserRef;
import dev.mosaicast.plugin.api.Users;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The host's user directory for plugins (ARCHITECTURE §8.8).
 *
 * <p>Deliberately thin, and the thinness is the contract. What a plugin learns about somebody else is
 * exactly {@link UserRef} — id, name, picture, role — and never the email, the provider or the
 * {@code external_id}, which stay server-side because {@code (provider, external_id)} is the login key
 * (§8.2). The avatar is the host's own path (§8.7), which is the only reason a picture can be handed over
 * here at all: a provider URL would carry the Discord snowflake to every viewer of a leaderboard.
 *
 * <p><strong>It resolves; it does not enumerate.</strong> There is no method that lists users and there is
 * not meant to be. A plugin may ask only about ids it already holds, and the only way it comes by them is
 * its own scope — which is what keeps this from being a directory dump behind a manifest flag.
 */
public class UsersImpl implements Users {

    /**
     * A ceiling on one lookup.
     *
     * <p>Not a security boundary — a plugin can call twice — but a leaderboard resolves a page of rows,
     * and an unbounded {@code IN (...)} built from plugin input is the kind of query that is fine until
     * the day somebody passes ten thousand ids.
     */
    static final int MAX_IDS = 500;

    private final UserRepository users;

    public UsersImpl(UserRepository users) {
        this.users = users;
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Absent, not redacted.</strong> An id that is unknown, erased or pseudonymised (§12.8) is
     * simply missing from the result — no null element, no tombstone. That is the shape {@code ctx.feeds}
     * already uses (§7.5), and it is what lets a leaderboard row outlive its author as §13 requires: the
     * aggregate stays and the person becomes whatever placeholder the plugin draws.
     */
    @Override
    public List<UserRef> resolve(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // Deduplicated before the query, because "duplicate ids resolve once" is part of the published
        // contract and a caller batching a leaderboard will pass the same author several times.
        Set<UUID> distinct = ids.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_IDS)
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return List.of();
        }
        return users.findAllById(distinct).stream().map(UsersImpl::toRef).toList();
    }

    private static UserRef toRef(User user) {
        return new UserRef(
                user.getId(),
                user.getDisplayName(),
                MeView.avatarUrlFor(user.getId()),
                user.getRole());
    }
}
