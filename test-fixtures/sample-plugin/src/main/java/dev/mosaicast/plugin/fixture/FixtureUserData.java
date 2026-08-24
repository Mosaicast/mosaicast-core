// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.UserDataHandler;
import org.pf4j.Extension;

/**
 * The fixture's account-deletion handler (ARCHITECTURE §12, SDK {@code UserDataHandler}).
 *
 * <p>Leaves a mark in its own doc store so the host's test can prove the handler actually ran — a plugin's
 * erasure is invisible to core by construction, which is the whole reason the contract exists.
 *
 * <p>Throws when the admin-editable {@code failErasure} config field is on, so the host's failure policy
 * (record a debt, retry, show it in admin) has something real to record.
 */
@Extension
public class FixtureUserData implements UserDataHandler {

    @Override
    public void eraseUser(String userId) {
        PluginContext ctx = FixturePlugin.shared;
        if (ctx == null) {
            throw new IllegalStateException("fixture was not registered");
        }
        if (ctx.config().get("failErasure", Boolean.class, false)) {
            throw new IllegalStateException("fixture was told to fail");
        }
        // Idempotent, as the contract requires: the same mark, however many times this is retried.
        ctx.store().put(Scope.site(), "erased:" + userId, true);
    }
}
