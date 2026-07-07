// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.dev;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Logs a loud, unmissable warning at startup while the {@code dev} profile (and its login bypass) is
 * active — so it can never be mistaken for a production boot (dev-login conditions, ARCHITECTURE §8).
 */
@Component
@Profile("dev")
public class DevProfileWarning {

    private static final Logger log = LoggerFactory.getLogger(DevProfileWarning.class);

    @PostConstruct
    void warn() {
        log.warn("""

                ****************************************************************************
                *  DEV PROFILE ACTIVE — the /api/auth/dev-login bypass is ENABLED.         *
                *  It mints a session as ANY role WITHOUT Discord. Never run this in prod.  *
                ****************************************************************************
                """);
    }
}
