// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the "this plugin still owes us an erasure" rows, and commits them (ARCHITECTURE §12).
 *
 * <p>{@code REQUIRES_NEW}, so they survive whatever happens next. The debt is the record that a deletion
 * was asked for, and a record that disappears together with the failure it exists to outlive is not one.
 * {@code AccountErasureService.erase} used to write them inside its own transaction beneath a comment
 * promising that "a crash between here and the last handler leaves a debt rather than a silence" — they
 * became visible only on the overall commit, so a crash or a rollback left exactly no debt (core#167).
 *
 * <p>A separate bean rather than an annotated method on {@code AccountErasureService}, for the same reason
 * {@link PluginErasureCall} is one: a self-invocation never passes through the transactional proxy, so the
 * annotation would be silently inert and the comment would be wrong a second time.
 */
@Component
public class ErasureDebtRecorder {

    private final PluginLoaderService plugins;
    private final UserDataErasureRepository erasures;

    public ErasureDebtRecorder(PluginLoaderService plugins, UserDataErasureRepository erasures) {
        this.plugins = plugins;
        this.erasures = erasures;
    }

    /**
     * Records one debt per plugin that can owe something, committed immediately.
     *
     * @param userId the account being deleted
     * @param owes   whether a given plugin can hold anything — the caller's rule, which is about load state
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, Predicate<PluginRegistration> owes) {
        for (PluginRegistration registration : plugins.all()) {
            String pluginId = registration.id();
            if (!owes.test(registration)) {
                continue;
            }
            if (erasures.findByUserIdAndPluginId(userId, pluginId).isEmpty()) {
                erasures.save(new UserDataErasure(userId, pluginId));
            }
        }
    }
}
