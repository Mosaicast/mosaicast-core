// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted fuzzy-binding suggestion (ARCHITECTURE §5.3): a planned episode that fuzzy-matches a feed
 * item by title, awaiting the podcaster's confirmation. Never auto-applied.
 */
@Entity
@Table(name = "binding_suggestion")
public class BindingSuggestion {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "feed_id", nullable = false, updatable = false)
    private UUID feedId;

    @Column(name = "planned_ref_id", nullable = false, updatable = false)
    private UUID plannedRefId;

    @Column(name = "raw_guid", nullable = false, updatable = false)
    private String rawGuid;

    @Column(name = "planned_title", nullable = false)
    private String plannedTitle;

    @Column(name = "raw_title", nullable = false)
    private String rawTitle;

    @Column(nullable = false)
    private double similarity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BindingSuggestion() {
        // for JPA
    }

    public BindingSuggestion(UUID feedId, ReconcileResult.Suggestion suggestion) {
        this.id = UUID.randomUUID();
        this.feedId = feedId;
        this.plannedRefId = suggestion.plannedRefId();
        this.rawGuid = suggestion.rawGuid();
        this.plannedTitle = suggestion.plannedTitle();
        this.rawTitle = suggestion.rawTitle();
        this.similarity = suggestion.similarity();
    }

    public UUID getId() {
        return id;
    }

    public UUID getFeedId() {
        return feedId;
    }

    public UUID getPlannedRefId() {
        return plannedRefId;
    }

    public String getRawGuid() {
        return rawGuid;
    }

    public String getPlannedTitle() {
        return plannedTitle;
    }

    public String getRawTitle() {
        return rawTitle;
    }

    public double getSimilarity() {
        return similarity;
    }
}
