// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

/**
 * Whether a first login may create a new account right now (ARCHITECTURE §18.3). An interface so the
 * account-merging rules do not depend on where the answer comes from — the site-access policy today.
 */
@FunctionalInterface
public interface SignupGate {

    /** A gate that never refuses: every first login creates an account, as before §18. */
    SignupGate OPEN = () -> true;

    /** Whether a brand-new account may be created now. */
    boolean allowsNewAccounts();
}
