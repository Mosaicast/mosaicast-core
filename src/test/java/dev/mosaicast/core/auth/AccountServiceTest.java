// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.ExplicitLinkRequiredException;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Risk-point unit tests for the account-merging rules (ARCHITECTURE §8.3, §13.5). Covers all branches:
 * known identity, cross-user link conflict, link-while-logged-in, the conservative verified-email variant
 * (require explicit linking, never merge silently), email normalization, and bootstrap-admin promotion.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private LinkedIdentityRepository identities;

    @Mock
    private UserNameHistoryRepository nameHistory;

    @Mock
    private dev.mosaicast.core.auth.avatar.AvatarService avatars;

    @Mock
    private dev.mosaicast.core.notification.NotificationService notifications;

    private AccountService service;

    /**
     * Real, not mocked: naming a new account is part of what {@code resolveLogin} does, and a stub would
     * assert that the merging rules call <em>something</em> rather than that a sign-up ends up with a name.
     */
    private DisplayNameService displayNames;

    @BeforeEach
    void setUp() {
        displayNames = new DisplayNameService(
                users, nameHistory, new DisplayNameProperties(null, null, null, List.of(), List.of()),
                notifications);
        service = new AccountService(users, identities, new AuthProperties("", ""), displayNames, avatars);
        lenient().when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identities.save(any(LinkedIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static IdentityClaim claim(String provider, String externalId, String email, boolean verified) {
        return new IdentityClaim(provider, externalId, email, verified, "Alex", null);
    }

    @Test
    void case1_knownIdentity_logsInExistingUser() {
        UUID userId = UUID.randomUUID();
        User existingUser = User.create(UUID.randomUUID(), "Alex", "alex", null, Role.FAN);
        LinkedIdentity identity = LinkedIdentity.link(userId, "discord", "E1", "old@x.io", false, null);
        when(identities.findByProviderAndExternalId("discord", "E1")).thenReturn(Optional.of(identity));
        when(users.findById(userId)).thenReturn(Optional.of(existingUser));

        User result = service.resolveLogin(claim("discord", "E1", "new@x.io", true), null);

        assertThat(result).isSameAs(existingUser);
        // Email/verification refreshed from the provider on login.
        assertThat(identity.getEmail()).isEqualTo("new@x.io");
        assertThat(identity.isEmailVerified()).isTrue();
        verify(users, never()).save(any()); // no new user
    }

    @Test
    void case2_newIdentityWhileLoggedIn_attachesToCurrentUser() {
        UUID currentUserId = UUID.randomUUID();
        User current = User.create(UUID.randomUUID(), "Alex", "alex", null, Role.PODCASTER);
        when(identities.findByProviderAndExternalId("patreon", "P9")).thenReturn(Optional.empty());
        when(users.findById(currentUserId)).thenReturn(Optional.of(current));

        User result = service.resolveLogin(claim("patreon", "P9", "a@x.io", false), currentUserId);

        assertThat(result).isSameAs(current);
        ArgumentCaptor<LinkedIdentity> saved = ArgumentCaptor.forClass(LinkedIdentity.class);
        verify(identities).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(current.getId());
        assertThat(saved.getValue().getProvider()).isEqualTo("patreon");
    }

    @Test
    void case1_linkingIdentityOwnedByAnotherUser_throwsConflict() {
        UUID currentUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        LinkedIdentity othersIdentity = LinkedIdentity.link(otherUserId, "discord", "E1", "b@x.io", true, null);
        when(identities.findByProviderAndExternalId("discord", "E1")).thenReturn(Optional.of(othersIdentity));

        // A logged-in user must NOT be switched into the account that already owns this identity.
        assertThatThrownBy(() -> service.resolveLogin(claim("discord", "E1", "x@x.io", true), currentUserId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void case3_anonymousVerifiedEmailMatch_requiresExplicitLink_doesNotMerge() {
        when(identities.findByProviderAndExternalId("google", "G7")).thenReturn(Optional.empty());
        when(identities.findByEmailAndEmailVerifiedTrue("a@x.io"))
                .thenReturn(List.of(
                        LinkedIdentity.link(UUID.randomUUID(), "discord", "E1", "a@x.io", true, null)));

        // §8.3 conservative variant: do not merge silently, do not create a duplicate — require linking.
        assertThatThrownBy(() -> service.resolveLogin(claim("google", "G7", "a@x.io", true), null))
                .isInstanceOf(ExplicitLinkRequiredException.class);
        verify(users, never()).save(any());
    }

    @Test
    void email_isNormalized_beforeMatchingAndStoring() {
        // Incoming mixed-case email matches a lowercase-stored verified identity → explicit link required,
        // proving the lookup is done on the normalized (lowercased) email.
        when(identities.findByProviderAndExternalId("discord", "E9")).thenReturn(Optional.empty());
        when(identities.findByEmailAndEmailVerifiedTrue("alice@x.io"))
                .thenReturn(List.of(
                        LinkedIdentity.link(UUID.randomUUID(), "patreon", "P1", "alice@x.io", true, null)));

        assertThatThrownBy(() -> service.resolveLogin(claim("discord", "E9", "Alice@X.IO", true), null))
                .isInstanceOf(ExplicitLinkRequiredException.class);
    }

    @Test
    void email_isStoredLowercased_onNewAccount() {
        when(identities.findByProviderAndExternalId("discord", "E10")).thenReturn(Optional.empty());
        when(identities.findByEmailAndEmailVerifiedTrue("bob@x.io")).thenReturn(List.of());

        service.resolveLogin(claim("discord", "E10", "BOB@X.io", true), null);

        ArgumentCaptor<LinkedIdentity> saved = ArgumentCaptor.forClass(LinkedIdentity.class);
        verify(identities).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@x.io");
    }

    @Test
    void case3_anonymousVerifiedNoMatch_createsNewUser() {
        when(identities.findByProviderAndExternalId("discord", "E2")).thenReturn(Optional.empty());
        when(identities.findByEmailAndEmailVerifiedTrue("fresh@x.io")).thenReturn(List.of());

        User result = service.resolveLogin(claim("discord", "E2", "fresh@x.io", true), null);

        assertThat(result.getRole()).isEqualTo(Role.FAN);
        verify(users).save(any(User.class));
    }

    @Test
    void case3_anonymousUnverifiedEmail_neverMerges_createsNewUser() {
        when(identities.findByProviderAndExternalId("discord", "E3")).thenReturn(Optional.empty());

        User result = service.resolveLogin(claim("discord", "E3", "a@x.io", false), null);

        assertThat(result.getRole()).isEqualTo(Role.FAN);
        verify(users).save(any(User.class));
        // An unverified email must not even be considered for merging.
        verify(identities, never()).findByEmailAndEmailVerifiedTrue(any());
    }

    @Test
    void bootstrapIdentity_isPromotedToAdmin() {
        AccountService bootstrapService =
                new AccountService(users, identities, new AuthProperties("discord", "ADMIN123"), displayNames, avatars);
        when(identities.findByProviderAndExternalId("discord", "ADMIN123")).thenReturn(Optional.empty());
        when(identities.findByEmailAndEmailVerifiedTrue(any())).thenReturn(List.of());

        User result = bootstrapService.resolveLogin(claim("discord", "ADMIN123", "boss@x.io", true), null);

        assertThat(result.getRole()).isEqualTo(Role.ADMIN);
    }
}
