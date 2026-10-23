// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

/**
 * An admin switched a plugin off (ARCHITECTURE §7.8).
 *
 * <p>Every request surface notices on its own, because each asks {@code active(id)}. What does not ask is
 * what is already running for the plugin: open channel subscriptions and pending {@code runAfter} tasks
 * (§7.9). This tells them, so switching a plugin off ends its live connections at once rather than at the
 * next heartbeat.
 *
 * @param pluginId the plugin that is now disabled
 */
public record PluginDisabledEvent(String pluginId) {
}
