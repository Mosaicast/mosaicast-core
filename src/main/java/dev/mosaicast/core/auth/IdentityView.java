// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.time.Instant;

/**
 * One row of the settings provider list (ARCHITECTURE §8.4): whether the current user has this provider
 * linked, and if so the captured email and when it was linked. The UI shows a green check when linked,
 * otherwise a "Connect" button (which starts the OAuth flow → merging case 2).
 *
 * @param provider the provider key (e.g. {@code discord})
 * @param linked   whether the current user has this provider linked
 * @param email    the captured email when linked, otherwise {@code null}
 * @param since    when it was linked, otherwise {@code null}
 */
public record IdentityView(String provider, boolean linked, String email, Instant since) {

    static IdentityView linked(LinkedIdentity identity) {
        return new IdentityView(identity.getProvider(), true, identity.getEmail(), identity.getCreatedAt());
    }

    static IdentityView notLinked(String provider) {
        return new IdentityView(provider, false, null, null);
    }
}
