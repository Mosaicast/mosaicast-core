// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link LegalPageTranslation}. */
public interface LegalPageTranslationRepository extends JpaRepository<LegalPageTranslation, UUID> {

    Optional<LegalPageTranslation> findByPageIdAndLocale(UUID pageId, String locale);

    List<LegalPageTranslation> findByPageId(UUID pageId);

    void deleteByPageId(UUID pageId);

    void deleteByPageIdAndLocale(UUID pageId, String locale);
}
