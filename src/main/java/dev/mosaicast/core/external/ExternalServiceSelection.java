// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Which provider an instance uses for one kind (ARCHITECTURE §12.7).
 *
 * <p>A {@code null} {@code providerId} and an absent row mean the same thing to a caller — no provider — but
 * they are different histories: the first is an admin who chose "none", the second is an admin who has never
 * opened the page. Keeping them apart costs nothing and makes the audit line honest.
 */
@Entity
@Table(name = "external_service_selection")
public class ExternalServiceSelection {

    @Id
    @Column(name = "kind", nullable = false)
    private String kind;

    /** {@code null} means the admin explicitly selected no provider. */
    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ExternalServiceSelection() {
        // for JPA
    }

    public ExternalServiceSelection(ExternalServiceKind kind, String providerId) {
        this.kind = kind.id();
        this.providerId = providerId;
    }

    public String getKind() {
        return kind;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
