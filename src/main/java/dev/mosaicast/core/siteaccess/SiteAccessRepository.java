// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import org.springframework.data.jpa.repository.JpaRepository;

/** The single {@link SiteAccess} row, when one has been saved. */
public interface SiteAccessRepository extends JpaRepository<SiteAccess, Short> {
}
