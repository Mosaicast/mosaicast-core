// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.dev;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Guards the {@code dev} profile (and with it the {@code /api/auth/dev-login} bypass): it logs a loud,
 * unmissable warning at startup, and it <strong>refuses to start</strong> when the profile looks like it
 * was switched on by accident (dev-login conditions, ARCHITECTURE §8).
 *
 * <p>The same two-key shape {@code mosaicast.feed.allow-private-targets} already uses (application.yml):
 * one variable is too easy to set while chasing something else and leave behind, and the failure mode is
 * silent — the site keeps working, only the login gate is gone. The second key says you meant it.
 *
 * <p>Configured Discord credentials are treated as a production signal on their own: an install that has
 * a real OAuth client has no business also offering a password-less ADMIN session, so that combination is
 * refused outright rather than confirmed away.
 */
@Component
@Profile("dev")
public class DevProfileWarning {

    private static final Logger log = LoggerFactory.getLogger(DevProfileWarning.class);

    private final boolean confirmed;
    private final String discordClientId;

    public DevProfileWarning(
            @Value("${mosaicast.security.dev-login-confirmed:${MOSAICAST_DEV_LOGIN_CONFIRMED:false}}")
            boolean confirmed,
            @Value("${DISCORD_CLIENT_ID:}") String discordClientId) {
        this.confirmed = confirmed;
        this.discordClientId = discordClientId;
    }

    @PostConstruct
    void warn() {
        if (discordClientId != null && !discordClientId.isBlank()) {
            throw new IllegalStateException(
                    "The 'dev' profile is active while DISCORD_CLIENT_ID is configured. The dev-login "
                            + "bypass mints an ADMIN session without Discord, so this combination is "
                            + "refused. Remove 'dev' from spring.profiles.active for this deployment.");
        }
        if (!confirmed) {
            throw new IllegalStateException(
                    "The 'dev' profile is active but MOSAICAST_DEV_LOGIN_CONFIRMED is not set to true. "
                            + "The profile enables /api/auth/dev-login, which mints a session as ANY role "
                            + "without Discord. Set MOSAICAST_DEV_LOGIN_CONFIRMED=true for a local run, or "
                            + "drop the profile.");
        }
        log.warn("""

                ****************************************************************************
                *  DEV PROFILE ACTIVE — the /api/auth/dev-login bypass is ENABLED.         *
                *  It mints a session as ANY role WITHOUT Discord. Never run this in prod.  *
                ****************************************************************************
                """);
    }
}
