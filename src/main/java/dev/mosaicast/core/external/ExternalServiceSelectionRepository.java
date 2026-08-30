// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage for the per-kind provider choice. */
public interface ExternalServiceSelectionRepository extends JpaRepository<ExternalServiceSelection, String> {
}
