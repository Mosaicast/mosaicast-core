// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One entry in the site's shared tag vocabulary (ARCHITECTURE §6.1): a canonical {@link TagKeys key} and
 * the display label kept from first use.
 *
 * <p>A tag exists here because something carried it — the feed, a podcaster, or a plugin tagging one of its
 * own subjects. Nothing deletes an entry as a side effect of dropping an assignment: the vocabulary is
 * shared, so removing a word from it is not one writer's call, and an entry with no assignments left is
 * simply a tag nothing is about yet.
 */
@Entity
@Table(name = "tag")
public class Tag {

    @Id
    @Column(name = "tag")
    private String tag;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tag() {
        // for JPA
    }

    public Tag(String tag, String label) {
        this.tag = tag;
        this.label = label;
        this.createdAt = Instant.now();
    }

    public String getTag() {
        return tag;
    }

    /**
     * The spelling shown to people, kept from whoever first used the tag.
     *
     * <p>Not updated by later writers on purpose: a vocabulary that re-cased itself every time a plugin sent
     * a different spelling would flicker between them, and the first use is at least a stable choice.
     */
    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
