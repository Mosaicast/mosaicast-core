// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import dev.mosaicast.core.web.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin user management (ARCHITECTURE §8.5 — "the admin promotes fans → podcasters"). ADMIN-only via the
 * {@code /api/admin/**} rule in {@code SecurityConfig}. Role changes take effect on the user's next request
 * (the per-request reload in {@code AuthenticatedUserFilter}), so no session surgery is needed.
 *
 * <p>Guards: an admin cannot change <em>their own</em> role (prevents self-lockout), and the <em>last</em>
 * ADMIN cannot be demoted (so the site always has one).
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);

    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final DisplayNameService displayNames;
    private final dev.mosaicast.core.notification.NotificationService notifications;

    public UserAdminController(UserRepository users, LinkedIdentityRepository identities,
                               DisplayNameService displayNames,
                               dev.mosaicast.core.notification.NotificationService notifications) {
        this.users = users;
        this.identities = identities;
        this.displayNames = displayNames;
        this.notifications = notifications;
    }

    /** A provider linked to the user, with the captured email (for the admin list). */
    public record IdentityRef(String provider, String email) {
    }

    /** A user as the admin list sees them; {@code role} is lower-cased to match the shell contract. */
    public record UserAdminView(
            UUID id, String displayName, String avatarUrl, String role, Instant createdAt,
            List<IdentityRef> identities) {
    }

    /** The role-change body — a lower-case role name ({@code admin}/{@code podcaster}/{@code fan}). */
    public record RoleRequest(@NotBlank String role) {
    }

    /**
     * The user list, paged and searchable (§8.5, §13 — list endpoints paginate from day one).
     *
     * <p>The search runs on the canonical key (§8.6), which is what makes it useful for the job this page
     * now does: an account reported for impersonation is found by typing the name it is imitating, even
     * though it is spelt with a Cyrillic character exactly so that it would not match.
     *
     * @param q    a name fragment, canonicalised before matching; blank lists everyone
     * @param page zero-based page index
     * @param size page size, clamped by {@link PagedResponse#MAX_PAGE_SIZE}
     */
    @GetMapping
    public PagedResponse<UserAdminView> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(
                PagedResponse.page(page), PagedResponse.size(size), Sort.by(Sort.Direction.ASC, "createdAt"));
        String fragment = DisplayNames.canonicalise(q == null ? "" : q);
        Page<User> found = fragment.isEmpty()
                ? users.findAll(pageable)
                : users.findByDisplayKeyContaining(fragment, pageable);
        // The identities of the whole page in one query: resolved per user they were 51 queries for the
        // default page and 201 for the largest, each on its own connection (core#195).
        java.util.Map<UUID, List<LinkedIdentity>> byUser = identities.findByUserIdIn(
                        found.getContent().stream().map(User::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(LinkedIdentity::getUserId));
        return PagedResponse.of(found, user -> toView(user, byUser.getOrDefault(user.getId(), List.of())));
    }

    /**
     * Walks a user's display name back to the previous one (ARCHITECTURE §8.6.1).
     *
     * <p>There is no endpoint that <em>sets</em> a name, and there is not meant to be. See
     * {@link DisplayNameService#revert} for why the asymmetry is the point.
     *
     * <p>Self-revert is not blocked the way self-role-change is: the guard on roles exists to stop an admin
     * locking themselves out of the site, and a name cannot do that. An admin walking back their own name
     * is just an admin using the feature.
     */
    @PostMapping("/{id}/name/revert")
    public MeView revertName(@PathVariable UUID id, Authentication authentication) {
        UUID adminId = CurrentUser.id(authentication).orElseThrow();
        displayNames.revert(id, adminId);
        return MeView.of(users.findById(id).orElseThrow(() -> new NotFoundException("No such user: " + id)));
    }

    /** The warning body — free text, unlike a system message (§17). */
    public record WarningRequest(@NotBlank String text) {
    }

    /**
     * Sends a user a warning (ARCHITECTURE §17).
     *
     * <p><strong>Free text, deliberately.</strong> A warning that cannot say what it is about is not a
     * warning, so this is the one place an admin's own words reach another user — and the difference from
     * §8.6.1 is worth stating: an admin may explain, and may still not choose somebody's name. The message
     * is attributable and logged like a role change (§8.5), because "they were told" is the point of it and
     * that needs a record outside the row the recipient can mark read.
     */
    @PostMapping("/{id}/warn")
    public ResponseEntity<Void> warn(@PathVariable UUID id, @Valid @RequestBody WarningRequest request,
                                     Authentication authentication) {
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("No such user: " + id));
        UUID adminId = CurrentUser.id(authentication).orElseThrow();
        notifications.adminWarning(user.getId(), request.text(), adminId);
        return ResponseEntity.noContent().build();
    }

    /**
     * One warning as the admin who sent it sees it.
     *
     * @param readAt when the user opened it, or null — the part that makes a warning a record rather than
     *               a gesture (§17)
     */
    public record WarningView(UUID id, String text, java.time.Instant createdAt,
                              java.time.Instant readAt) {
    }

    /**
     * The warnings this user has been sent (ARCHITECTURE §17).
     *
     * <p>Fetched per user on demand rather than counted into the list, so opening the admin page does not
     * cost a query per row for a surface most visits never look at.
     */
    @GetMapping("/{id}/warnings")
    public List<WarningView> warnings(@PathVariable UUID id) {
        return notifications.warningsFor(id).stream()
                .map(n -> new WarningView(n.getId(), n.getPayload().getOrDefault("text", ""),
                        n.getCreatedAt(), n.getReadAt()))
                .toList();
    }

    /**
     * Changes a user's role (§8.5).
     *
     * <p>Transactional and row-locking, because the "last admin" guard is a read-then-write: without a lock
     * two admins demoting each other concurrently both read two admins, both commit, and the site is left
     * with none. {@code findByRoleOrderByIdAsc} takes a write lock on the ADMIN rows, so the second request
     * waits and then sees the row set the first one left behind.
     */
    @PutMapping("/{id}/role")
    @org.springframework.transaction.annotation.Transactional
    public MeView setRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request,
                          Authentication authentication) {
        Role newRole = Roles.parse(request.role());
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("No such user: " + id));
        UUID currentUserId = CurrentUser.id(authentication).orElseThrow();

        if (user.getId().equals(currentUserId)) {
            throw new ConflictException("You cannot change your own role.");
        }
        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
            // Locked, not merely counted — see UserRepository#findByRoleOrderByIdAsc.
            if (users.findByRoleOrderByIdAsc(Role.ADMIN).size() <= 1) {
                throw new ConflictException("Cannot demote the last admin.");
            }
        }

        Role previous = user.getRole();
        user.changeRole(newRole);
        users.save(user);
        // Who can do what is worth a permanent record: display name for the reader, ids for correlation.
        log.info("Role of '{}' ({}) changed {} → {} by admin {}",
                user.getDisplayName(), user.getId(), previous, newRole, currentUserId);
        return MeView.of(user);
    }

    private UserAdminView toView(User user) {
        return toView(user, identities.findByUserId(user.getId()));
    }

    private UserAdminView toView(User user, List<LinkedIdentity> linked) {
        List<IdentityRef> refs = linked.stream()
                .map(i -> new IdentityRef(i.getProvider(), i.getEmail()))
                .toList();
        return new UserAdminView(
                user.getId(), user.getDisplayName(), MeView.avatarUrlFor(user.getId()),
                user.getRole().name().toLowerCase(Locale.ROOT), user.getCreatedAt(), refs);
    }

}
