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
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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

    private final UserRepository users;
    private final LinkedIdentityRepository identities;

    public UserAdminController(UserRepository users, LinkedIdentityRepository identities) {
        this.users = users;
        this.identities = identities;
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

    @GetMapping
    public List<UserAdminView> list() {
        return users.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream().map(this::toView).toList();
    }

    @PutMapping("/{id}/role")
    public MeView setRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request,
                          Authentication authentication) {
        Role newRole = parseRole(request.role());
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("No such user: " + id));
        UUID currentUserId = CurrentUser.id(authentication).orElseThrow();

        if (user.getId().equals(currentUserId)) {
            throw new ConflictException("You cannot change your own role.");
        }
        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN && users.countByRole(Role.ADMIN) <= 1) {
            throw new ConflictException("Cannot demote the last admin.");
        }

        user.changeRole(newRole);
        users.save(user);
        return MeView.of(user);
    }

    private UserAdminView toView(User user) {
        List<IdentityRef> refs = identities.findByUserId(user.getId()).stream()
                .map(i -> new IdentityRef(i.getProvider(), i.getEmail()))
                .toList();
        return new UserAdminView(
                user.getId(), user.getDisplayName(), user.getAvatarUrl(),
                user.getRole().name().toLowerCase(), user.getCreatedAt(), refs);
    }

    private static Role parseRole(String role) {
        try {
            return Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConflictException(
                    "Unknown role '" + role + "'. Use one of: admin, podcaster, fan.");
        }
    }
}
