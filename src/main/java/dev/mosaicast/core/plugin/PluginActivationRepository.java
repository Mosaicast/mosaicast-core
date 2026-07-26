// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PluginActivation} — only plugins an admin explicitly switched have a row. */
public interface PluginActivationRepository extends JpaRepository<PluginActivation, String> {
}
