// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link LegalPage}. */
public interface LegalPageRepository extends JpaRepository<LegalPage, UUID> {

    Optional<LegalPage> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** All pages in footer order (ARCHITECTURE §12.6 — sortable). */
    List<LegalPage> findAllByOrderBySortOrderAscSlugAsc();
}
