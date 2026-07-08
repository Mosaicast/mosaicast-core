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

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public MeView me(Authentication authentication) {
        return MeView.of(accounts.requireUser(currentUserId(authentication)));
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

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
