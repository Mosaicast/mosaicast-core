// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The Discord client registration (core#191): present exactly when a client id is configured, and pointed
 * at Discord's own endpoints with the narrow scopes the account model needs.
 */
class OAuth2ClientConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(OAuth2ClientConfig.class);

    @Test
    void noClientIdMeansNoRegistrationAtAll() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class));
        // An .env line left as `DISCORD_CLIENT_ID= ` is "not configured", not a client called " ".
        runner.withPropertyValues("DISCORD_CLIENT_ID=  ")
                .run(context -> assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class));
    }

    @Test
    void aClientIdRegistersDiscordWithItsOwnEndpointsAndNarrowScopes() {
        runner.withPropertyValues("DISCORD_CLIENT_ID=123", "DISCORD_CLIENT_SECRET=shh").run(context -> {
            ClientRegistration discord =
                    context.getBean(ClientRegistrationRepository.class).findByRegistrationId("discord");

            assertThat(discord.getClientId()).isEqualTo("123");
            assertThat(discord.getClientSecret()).isEqualTo("shh");
            assertThat(discord.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
            assertThat(discord.getScopes()).containsExactlyInAnyOrder("identify", "email");
            assertThat(discord.getProviderDetails().getAuthorizationUri())
                    .isEqualTo("https://discord.com/oauth2/authorize");
            assertThat(discord.getProviderDetails().getTokenUri()).isEqualTo("https://discord.com/api/oauth2/token");
            // The account is keyed on Discord's stable numeric id, never on the renameable username.
            assertThat(discord.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                    .isEqualTo("id");
            assertThat(discord.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/discord");
        });
    }
}
