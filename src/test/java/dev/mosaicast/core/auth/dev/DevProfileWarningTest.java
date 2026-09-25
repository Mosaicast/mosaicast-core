// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.dev;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The dev profile's start-up gate, from the side {@code DevLoginAbsentTest} does not cover (core#191).
 *
 * <p>That test proves the bypass is absent without the profile. These prove the profile itself refuses to
 * come up unless someone confirmed it, and refuses outright next to real OAuth credentials — the two things
 * standing between a copied {@code SPRING_PROFILES_ACTIVE=dev} and a production site with a password-less
 * ADMIN login.
 */
class DevProfileWarningTest {

    @Test
    void theProfileWithoutConfirmationRefusesToStart() {
        assertThatThrownBy(() -> new DevProfileWarning(false, "").warn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOSAICAST_DEV_LOGIN_CONFIRMED");
    }

    @Test
    void discordCredentialsAreRefusedEvenWhenConfirmed() {
        // Confirmation does not make this combination acceptable: a real OAuth client is a production signal.
        assertThatThrownBy(() -> new DevProfileWarning(true, "123456789").warn())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISCORD_CLIENT_ID");
    }

    @Test
    void aBlankClientIdIsNotAProductionSignal() {
        // An .env with `DISCORD_CLIENT_ID=` left empty is the ordinary local setup, not a configured client.
        assertThatCode(() -> new DevProfileWarning(true, "  ").warn()).doesNotThrowAnyException();
    }

    @Test
    void aConfirmedLocalRunStarts() {
        assertThatCode(() -> new DevProfileWarning(true, "").warn()).doesNotThrowAnyException();
        assertThatCode(() -> new DevProfileWarning(true, null).warn()).doesNotThrowAnyException();
    }
}
