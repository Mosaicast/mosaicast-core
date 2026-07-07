// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a login into a {@link User}, applying the account-merging rules (ARCHITECTURE §8.3) — a
 * security risk point. The rule in short: <strong>auto-link only with two verified emails or a
 * logged-in user; otherwise a separate account</strong> (the user can link explicitly in settings).
 *
 * <ol>
 *   <li>{@code (provider, external_id)} already exists → log that user in (refresh email/verified).</li>
 *   <li>{@code (provider, external_id)} new and a user is logged in (linking from settings) → attach the
 *       identity to that user. Always safe.</li>
 *   <li>{@code (provider, external_id)} new and anonymous → link to an existing account only if the
 *       incoming email is verified AND another verified identity already has that email; otherwise create
 *       a new user. An unverified email never merges.</li>
 * </ol>
 *
 * <p>The configured bootstrap identity (§8.5) is granted {@code ADMIN} on login.
 */
@Service
public class AccountService {

    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final AuthProperties auth;

    public AccountService(UserRepository users, LinkedIdentityRepository identities, AuthProperties auth) {
        this.users = users;
        this.identities = identities;
        this.auth = auth;
    }

    /**
     * Resolves the user for a login.
     *
     * @param claim         the provider-asserted identity
     * @param currentUserId the id of the already-logged-in user when this is a link-from-settings flow, or
     *                      {@code null} for an anonymous login
     * @return the user to authenticate as
     */
    @Transactional
    public User resolveLogin(IdentityClaim claim, @Nullable UUID currentUserId) {
        User user = resolve(claim, currentUserId);
        applyBootstrap(user, claim);
        return user;
    }

    private User resolve(IdentityClaim claim, @Nullable UUID currentUserId) {
        // Case 1: known identity → log in, refreshing the captured email/verification.
        var existing = identities.findByProviderAndExternalId(claim.provider(), claim.externalId());
        if (existing.isPresent()) {
            LinkedIdentity identity = existing.get();
            identity.refresh(claim.email(), claim.emailVerified());
            identities.save(identity);
            return users.findById(identity.getUserId())
                    .orElseThrow(() -> new IllegalStateException("Identity references a missing user"));
        }

        // Case 2: new identity while logged in → attach to the current user (linking). Always safe.
        if (currentUserId != null) {
            User current = users.findById(currentUserId)
                    .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists"));
            attach(current.getId(), claim);
            return current;
        }

        // Case 3: new identity, anonymous → auto-link only when both sides are verified.
        if (claim.emailVerified() && claim.email() != null && !claim.email().isBlank()) {
            List<LinkedIdentity> verifiedMatches = identities.findByEmailAndEmailVerifiedTrue(claim.email());
            if (!verifiedMatches.isEmpty()) {
                UUID targetUserId = verifiedMatches.get(0).getUserId();
                attach(targetUserId, claim);
                return users.findById(targetUserId)
                        .orElseThrow(() -> new IllegalStateException("Matched user no longer exists"));
            }
        }

        // Otherwise: a brand-new account (default role FAN; the bootstrap identity is promoted below).
        User created = users.save(User.create(claim.displayName(), claim.avatarUrl(), Role.FAN));
        attach(created.getId(), claim);
        return created;
    }

    /** The user by id, or a 404. */
    @Transactional(readOnly = true)
    public User requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    /** A user's linked identities (for the settings provider list, §8.4). */
    @Transactional(readOnly = true)
    public List<LinkedIdentity> identitiesOf(UUID userId) {
        return identities.findByUserId(userId);
    }

    /**
     * Unlinks a provider from a user. The <strong>last remaining identity cannot be removed</strong> —
     * that would lock the user out (§8.4).
     */
    @Transactional
    public void unlink(UUID userId, String provider) {
        LinkedIdentity identity = identities.findByUserIdAndProvider(userId, provider)
                .orElseThrow(() -> new NotFoundException("No " + provider + " identity linked"));
        if (identities.countByUserId(userId) <= 1) {
            throw new ConflictException("Cannot remove the last identity — you would be locked out");
        }
        identities.delete(identity);
    }

    private void attach(UUID userId, IdentityClaim claim) {
        identities.save(LinkedIdentity.link(
                userId, claim.provider(), claim.externalId(), claim.email(), claim.emailVerified()));
    }

    /** Grants ADMIN to the configured bootstrap identity on login (§8.5). */
    private void applyBootstrap(User user, IdentityClaim claim) {
        if (auth.isBootstrapAdmin(claim.provider(), claim.externalId()) && user.getRole() != Role.ADMIN) {
            user.changeRole(Role.ADMIN);
            users.save(user);
        }
    }
}
