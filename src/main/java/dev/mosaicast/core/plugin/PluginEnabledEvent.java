// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * An admin switched a plugin on (ARCHITECTURE §7.8).
 *
 * <p>An event rather than a call, because the listener that matters — replaying the account erasures this
 * plugin could not be asked to carry out while it was off (§12) — reaches the plugin loader, which reaches
 * {@link PluginSettingsService}. A direct call would close that circle; a published event leaves the
 * dependency pointing one way.
 *
 * @param pluginId the plugin that is now enabled
 */
public record PluginEnabledEvent(String pluginId) {
}
