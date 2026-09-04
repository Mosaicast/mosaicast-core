// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.ExplicitLinkRequiredException;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a login into a {@link User}, applying the account-merging rules (ARCHITECTURE §8.3) — a
 * security risk point. The rule in short: <strong>auto-link only with two verified emails or a
 * logged-in user; otherwise a separate account</strong> (the user can link explicitly in settings).
 *
 * <ol>
 *   <li>{@code (provider, external_id)} already exists → log that user in (refresh email/verified). If a
 *       <em>different</em> user is logged in and tries to link it, that is a conflict (an identity belongs
 *       to exactly one account), not a silent account switch.</li>
 *   <li>{@code (provider, external_id)} new and a user is logged in (linking from settings) → attach the
 *       identity to that user.</li>
 *   <li>{@code (provider, external_id)} new and anonymous → if a verified email matches an existing verified
 *       identity, do <strong>not</strong> merge silently — require explicit linking (the conservative §8.3
 *       variant); otherwise create a new user. An unverified email never triggers this.</li>
 * </ol>
 *
 * <p>The stable key is {@code (provider, external_id)}, never the email (§8.2), so re-login survives an
 * email change at the provider (case 1 short-circuits before any email logic). The configured bootstrap
 * identity (§8.5) is granted {@code ADMIN} on login.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final AuthProperties auth;
    private final DisplayNameService displayNames;

    public AccountService(UserRepository users, LinkedIdentityRepository identities, AuthProperties auth,
                          DisplayNameService displayNames) {
        this.users = users;
        this.identities = identities;
        this.auth = auth;
        this.displayNames = displayNames;
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
        String email = normalizeEmail(claim.email());
        var existing = identities.findByProviderAndExternalId(claim.provider(), claim.externalId());
        if (existing.isPresent()) {
            LinkedIdentity identity = existing.get();
            // An identity belongs to exactly one account. A logged-in user trying to link an identity
            // owned by someone else must not be silently switched into that other account.
            if (currentUserId != null && !identity.getUserId().equals(currentUserId)) {
                throw new ConflictException(
                        "This " + claim.provider() + " account is already linked to another user");
            }
            identity.refresh(email, claim.emailVerified());
            identities.save(identity);
            return users.findById(identity.getUserId())
                    .orElseThrow(() -> new IllegalStateException("Identity references a missing user"));
        }

        // Case 2: new identity while logged in → attach to the current user (linking). Always safe.
        if (currentUserId != null) {
            User current = users.findById(currentUserId)
                    .orElseThrow(() -> new IllegalStateException("Logged-in user no longer exists"));
            attach(current.getId(), claim, email);
            log.info("Linked a {} identity to account {}", claim.provider(), current.getId());
            return current;
        }

        // Case 3: new identity, anonymous → auto-link only when both sides are verified.
        if (claim.emailVerified() && email != null
                && !identities.findByEmailAndEmailVerifiedTrue(email).isEmpty()) {
            throw new ExplicitLinkRequiredException(
                    "An account with this email already exists. Log in with your existing method, then link "
                            + claim.provider() + " in settings.");
        }

        // Otherwise: a brand-new account (default role FAN; the bootstrap identity is promoted below).
        //
        // The provider's name is a *proposal*, not the name (§8.6). It is prefilled once and never
        // overwritten by a later login, and it has to survive being unusable: display names are unique now,
        // so a second Discord user called "alex" would otherwise be unable to sign in at all. `initial`
        // therefore falls back rather than refusing — a naming policy must never become a login failure.
        UUID id = UUID.randomUUID();
        DisplayNameService.Name name = displayNames.initial(id, claim.displayName());
        User created = users.save(User.create(id, name.display(), name.key(), claim.avatarUrl(), Role.FAN));
        attach(created.getId(), claim, email);
        // No email in the log: an account id and the provider identify the event without storing a
        // personal identifier in a table an operator browses casually.
        log.info("New account '{}' ({}) created via {}", created.getDisplayName(), created.getId(),
                claim.provider());
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

    private void attach(UUID userId, IdentityClaim claim, String normalizedEmail) {
        identities.save(LinkedIdentity.link(
                userId, claim.provider(), claim.externalId(), normalizedEmail, claim.emailVerified()));
    }

    /** Normalizes an email for storage and matching (trim + lowercase); null/blank yields null. */
    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Grants ADMIN to the configured bootstrap identity on login (§8.5). */
    private void applyBootstrap(User user, IdentityClaim claim) {
        if (auth.isBootstrapAdmin(claim.provider(), claim.externalId()) && user.getRole() != Role.ADMIN) {
            user.changeRole(Role.ADMIN);
            users.save(user);
        }
    }
}
