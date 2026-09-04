// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The current user's account (ARCHITECTURE §8.4/§8.5): profile, the provider list with linked flags, and
 * unlinking (with last-identity lockout protection). Linking a new provider is done by starting the OAuth
 * flow while logged in (merging case 2), so there is no explicit "link" endpoint here.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    /** Providers offered in the settings UI in v1. Patreon/Google are prepared but not yet built (§8.1). */
    private static final List<String> SUPPORTED_PROVIDERS = List.of("discord");

    private final AccountService accounts;
    private final DisplayNameService displayNames;
    private final dev.mosaicast.core.erasure.AccountErasureService erasures;

    public MeController(AccountService accounts, DisplayNameService displayNames,
                        dev.mosaicast.core.erasure.AccountErasureService erasures) {
        this.accounts = accounts;
        this.displayNames = displayNames;
        this.erasures = erasures;
    }

    @GetMapping
    public MeView me(Authentication authentication) {
        return MeView.of(accounts.requireUser(currentUserId(authentication)));
    }

    /** A requested display name (§8.6). */
    public record ProfileRequest(@jakarta.validation.constraints.NotBlank String displayName) {
    }

    /**
     * Changes the caller's display name (ARCHITECTURE §8.6).
     *
     * <p>Refusals are RFC 7807 with a stable type per reason, because the four are not one error: too long,
     * not available, already taken and changed too recently need four different things from the person
     * reading them, and the UI translates on the type rather than on an English sentence (§13).
     */
    @org.springframework.web.bind.annotation.PatchMapping
    public MeView updateProfile(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                                ProfileRequest request, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        displayNames.rename(userId, request.displayName());
        return MeView.of(accounts.requireUser(userId));
    }

    @GetMapping("/identities")
    public List<IdentityView> identities(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Map<String, LinkedIdentity> linked = accounts.identitiesOf(userId).stream()
                .collect(java.util.stream.Collectors.toMap(LinkedIdentity::getProvider, i -> i, (a, b) -> a));
        return SUPPORTED_PROVIDERS.stream()
                .map(provider -> linked.containsKey(provider)
                        ? IdentityView.linked(linked.get(provider))
                        : IdentityView.notLinked(provider))
                .toList();
    }

    @DeleteMapping("/identities/{provider}")
    public ResponseEntity<Void> unlink(@PathVariable String provider, Authentication authentication) {
        accounts.unlink(currentUserId(authentication), provider);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes the caller's account (ARCHITECTURE §12).
     *
     * <p>Core drops what it owns — identities, tokens, listening progress and the {@code USER}-scope
     * documents it holds on plugins' behalf — and asks every installed plugin to erase or pseudonymise
     * what it holds in its own tables and files, because core provisioned those without ever learning
     * which column is a person.
     *
     * <p><strong>The answer says what is outstanding.</strong> A plugin whose handler threw, or one an
     * operator had switched off, leaves a recorded debt that is retried; reporting the deletion as complete
     * when it is not would be the lie the whole flow exists to avoid. The session ends either way: the
     * account is gone, whatever is still owed.
     */
    @DeleteMapping
    public DeletionReceipt delete(Authentication authentication,
                                  jakarta.servlet.http.HttpServletRequest request) {
        UUID userId = currentUserId(authentication);
        List<String> outstanding = erasures.erase(userId);
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return new DeletionReceipt(outstanding.isEmpty(), outstanding);
    }

    /**
     * What actually happened, rather than a bare 204.
     *
     * @param complete    whether every plugin finished its part
     * @param outstanding the plugins that have not — retried by the host, and visible to an admin
     */
    public record DeletionReceipt(boolean complete, List<String> outstanding) {
    }

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
