// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.UserRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The host's user directory (ARCHITECTURE §8.8). What is asserted here is mostly what it does
 * <em>not</em> hand over — the surface is small on purpose and staying small is the contract.
 */
@ExtendWith(MockitoExtension.class)
class UsersImplTest {

    @Mock
    private UserRepository users;

    private UsersImpl directory() {
        return new UsersImpl(users);
    }

    private static User user(UUID id, String name) {
        return User.create(id, name, name.toLowerCase(java.util.Locale.ROOT), null, Role.FAN);
    }

    @Test
    void resolvesIdsToNamesAndHostRelativeAvatars() {
        UUID id = UUID.randomUUID();
        when(users.findAllById(any())).thenReturn(List.of(user(id, "Ned")));

        List<UserRef> refs = directory().resolve(List.of(id));

        assertThat(refs).singleElement().satisfies(ref -> {
            assertThat(ref.id()).isEqualTo(id);
            assertThat(ref.displayName()).isEqualTo("Ned");
            // Host-relative, never a provider URL — that is what makes handing a picture over safe (§8.7).
            assertThat(ref.avatarUrl()).isEqualTo("/api/users/" + id + "/avatar");
            assertThat(ref.role()).isEqualTo(Role.FAN);
        });
    }

    @Test
    void anUnresolvableIdIsAbsentRatherThanATombstone() {
        UUID known = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(users.findAllById(any())).thenReturn(List.of(user(known, "Ned")));

        List<UserRef> refs = directory().resolve(List.of(known, gone));

        // Absent, not redacted (§8.8): unknown, erased and pseudonymised all look the same, so the answer
        // cannot be used to tell which. The result is shorter than the request and not aligned with it.
        assertThat(refs).hasSize(1);
        assertThat(refs).noneMatch(ref -> ref.id().equals(gone));
    }

    @Test
    void duplicateIdsResolveOnce() {
        UUID id = UUID.randomUUID();
        when(users.findAllById(any())).thenReturn(List.of(user(id, "Ned")));

        assertThat(directory().resolve(List.of(id, id, id))).hasSize(1);
        assertThat(capturedIds()).containsExactly(id);
    }

    @Test
    void anEmptyOrNullRequestCostsNoQuery() {
        // The normal state of a leaderboard nobody has played yet.
        assertThat(directory().resolve(List.of())).isEmpty();
        assertThat(directory().resolve(null)).isEmpty();
        verify(users, org.mockito.Mockito.never()).findAllById(any());
    }

    @Test
    void nullIdsInTheRequestAreSkippedRatherThanBlowingUp() {
        UUID id = UUID.randomUUID();
        when(users.findAllById(any())).thenReturn(List.of(user(id, "Ned")));
        assertThat(directory().resolve(Arrays.asList(id, null))).hasSize(1);
    }

    @Test
    void theBatchIsClampedSoPluginInputCannotBuildAnUnboundedQuery() {
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < UsersImpl.MAX_IDS + 50; i++) {
            many.add(UUID.randomUUID());
        }
        when(users.findAllById(any())).thenReturn(List.of());

        directory().resolve(many);

        assertThat(capturedIds()).hasSize(UsersImpl.MAX_IDS);
    }

    private List<UUID> capturedIds() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(users).findAllById(captor.capture());
        return new ArrayList<>(captor.getValue());
    }
}
