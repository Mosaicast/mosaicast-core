// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the single-row {@link SiteConfig}. */
public interface SiteConfigRepository extends JpaRepository<SiteConfig, Short> {
}
