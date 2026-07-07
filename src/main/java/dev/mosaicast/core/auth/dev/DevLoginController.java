// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.dev;

import dev.mosaicast.core.auth.AccountService;
import dev.mosaicast.core.auth.IdentityClaim;
import dev.mosaicast.core.auth.MeView;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.plugin.api.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <strong>DEVELOPMENT ONLY.</strong> Mints a session as any role without Discord, so role-based UI
 * (slot {@code visibleTo}, admin areas) can be exercised locally (ARCHITECTURE §8, dev-login conditions).
 *
 * <p>This bean — and therefore the endpoint — exists <strong>only under the {@code dev} Spring profile</strong>
 * ({@link Profile}). It is structurally absent in production: not a runtime flag, not an env toggle. There
 * is deliberately no corresponding entry in {@code .env.example} or {@code docker-compose.yml}.
 */
@RestController
@Profile("dev")
public class DevLoginController {

    private static final String DEV_PROVIDER = "dev";

    private final AccountService accounts;
    private final UserRepository users;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public DevLoginController(AccountService accounts, UserRepository users) {
        this.accounts = accounts;
        this.users = users;
    }

    /**
     * Logs in as a stable dev user for the requested role (default {@code ADMIN}).
     *
     * @param role     one of {@code admin}, {@code podcaster}, {@code fan} (case-insensitive)
     * @param request  the current request (to persist the session)
     * @param response the current response (to write the session cookie)
     * @return the resulting current user
     */
    @PostMapping("/api/auth/dev-login")
    public MeView devLogin(
            @RequestParam(defaultValue = "admin") String role,
            HttpServletRequest request,
            HttpServletResponse response) {

        Role selected = parseRole(role);
        // A stable dev user per role: (provider=dev, external_id=<role>).
        User user = accounts.resolveLogin(
                new IdentityClaim(DEV_PROVIDER, selected.name(), null, false, "Dev " + selected.name(), null),
                null);
        if (user.getRole() != selected) {
            user.changeRole(selected);
            users.save(user);
        }

        establishSession(user, selected, request, response);
        return MeView.of(user);
    }

    private void establishSession(User user, Role role, HttpServletRequest request, HttpServletResponse response) {
        var authorities = Set.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.getId().toString(), null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private static Role parseRole(String role) {
        try {
            return Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown role '" + role + "'. Use one of: " + List.of(Role.values()));
        }
    }
}
