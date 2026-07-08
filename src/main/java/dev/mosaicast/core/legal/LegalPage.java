// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A legal/static page (ARCHITECTURE §12.6). Mosaicast ships the mechanism, not the texts: an admin creates
 * any number of markdown pages, shown as footer links. The optional {@code roleMarker} lets the consent
 * service link to the {@code privacy} page; bodies are translated per locale ({@link LegalPageTranslation}).
 */
@Entity
@Table(name = "legal_page")
public class LegalPage {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "role_marker")
    private String roleMarker;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LegalPage() {
        // for JPA
    }

    public LegalPage(String slug, String roleMarker, int sortOrder) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.roleMarker = roleMarker;
        this.sortOrder = sortOrder;
    }

    public void update(String roleMarker, int sortOrder) {
        this.roleMarker = roleMarker;
        this.sortOrder = sortOrder;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getRoleMarker() {
        return roleMarker;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
