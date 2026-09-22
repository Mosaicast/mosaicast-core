// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import dev.mosaicast.core.plugin.PluginExtensions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls one plugin's {@code UserDataHandler} in a transaction of its own (ARCHITECTURE §12).
 *
 * <p>It exists for one reason: a handler that fails must not take the deletion down with it.
 * {@code AccountErasureService.erase} runs in a single transaction, and a plugin handler writing through
 * {@code PluginDataService} — which is {@code @Transactional} with the default {@code REQUIRED} — joins
 * that same transaction. When such a write throws, Spring marks the <em>outer</em> transaction
 * rollback-only. {@code attempt()} then catches the exception, records the debt, finishes the deletion and
 * returns normally, and the commit fails with {@code UnexpectedRollbackException}: the account is not
 * deleted, the debt row is not written, and the caller gets a 500 without its session being ended. That is
 * precisely the silent skip the erasure flow exists to prevent.
 *
 * <p>{@code REQUIRES_NEW} gives the handler its own suspendable transaction, so its failure rolls back only
 * its own partial writes. The outer transaction stays clean, {@code attempt()} can record
 * {@code FAILED} where a retry will find it, and the rest of the deletion still commits.
 *
 * <p>A separate bean rather than an annotation on a private method, because a self-invocation inside
 * {@code AccountErasureService} would never pass through the transactional proxy and the annotation would
 * be silently inert — the failure mode being fixed here, twice over.
 */
@Component
public class PluginErasureCall {

    private final PluginExtensions extensions;

    public PluginErasureCall(PluginExtensions extensions) {
        this.extensions = extensions;
    }

    /**
     * Runs every {@code UserDataHandler} of one plugin for one user, in a fresh transaction.
     *
     * @param pluginId the plugin to ask
     * @param userId   the user whose data must go, as a string — the handler's own key
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void eraseUserData(String pluginId, String userId) {
        extensions.eraseUserData(pluginId, userId);
    }
}
