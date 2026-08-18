// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PluginBlobGrant} — one optional row per plugin. */
public interface PluginBlobGrantRepository extends JpaRepository<PluginBlobGrant, String> {
}
