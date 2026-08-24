// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import dev.mosaicast.core.plugin.PluginEnabledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Settles a plugin's outstanding account erasures when an operator switches it back on (§12).
 *
 * <p>The disabled-plugin case is the one the whole record exists for: its tables and files are still there,
 * its extensions are not loaded, so a deletion that ran while it was off could not reach its data. Waiting
 * for the hourly sweep would work; doing it at the moment the plugin can answer means an operator who
 * re-enables a plugin sees the debt clear rather than lingering for an hour with no explanation.
 */
@Component
public class ErasureReplayOnEnable {

    private static final Logger log = LoggerFactory.getLogger(ErasureReplayOnEnable.class);

    private final AccountErasureService erasures;

    public ErasureReplayOnEnable(AccountErasureService erasures) {
        this.erasures = erasures;
    }

    @EventListener
    public void onEnabled(PluginEnabledEvent event) {
        try {
            int settled = erasures.replay(event.pluginId());
            if (settled > 0) {
                log.info("Settled {} outstanding account erasure(s) of plugin '{}'", settled,
                        event.pluginId());
            }
        } catch (RuntimeException e) {
            // The plugin is enabled either way; a failed replay stays a recorded debt and is retried.
            log.warn("Could not replay erasures of plugin '{}': {}", event.pluginId(), e.getMessage());
        }
    }
}
