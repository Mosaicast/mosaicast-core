// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One locale's body of a {@link LegalPage} (ARCHITECTURE §12.6/§12.7): one logical page, one markdown body
 * per language, served in the active UI locale with fallback to the default.
 */
@Entity
@Table(name = "legal_page_translation")
public class LegalPageTranslation {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @Column(nullable = false, updatable = false)
    private String locale;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String markdown;

    protected LegalPageTranslation() {
        // for JPA
    }

    public LegalPageTranslation(UUID pageId, String locale, String title, String markdown) {
        this.id = UUID.randomUUID();
        this.pageId = pageId;
        this.locale = locale;
        this.title = title;
        this.markdown = markdown;
    }

    public void update(String title, String markdown) {
        this.title = title;
        this.markdown = markdown;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getLocale() {
        return locale;
    }

    public String getTitle() {
        return title;
    }

    public String getMarkdown() {
        return markdown;
    }
}
