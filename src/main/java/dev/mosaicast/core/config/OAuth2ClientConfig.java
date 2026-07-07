// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Registers the Discord OAuth2 client (ARCHITECTURE §8.1) — but only when credentials are configured, so
 * the app still boots for local/dev/CI runs without them (the {@code dev} login bypass covers those). The
 * registration is built in code (not {@code application.yml}) precisely so its absence is clean when the
 * credentials are blank.
 *
 * <p>Patreon and Google are intentionally <em>not</em> registered yet (§8.1 — prepared, not built).
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    @ConditionalOnExpression("'${DISCORD_CLIENT_ID:}'.trim() != ''")
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${DISCORD_CLIENT_ID:}") String clientId,
            @Value("${DISCORD_CLIENT_SECRET:}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(discord(clientId, clientSecret));
    }

    private static ClientRegistration discord(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId("discord")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientName("Discord")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/discord")
                .scope("identify", "email")
                .authorizationUri("https://discord.com/oauth2/authorize")
                .tokenUri("https://discord.com/api/oauth2/token")
                .userInfoUri("https://discord.com/api/users/@me")
                .userNameAttributeName("id")
                .build();
    }
}
