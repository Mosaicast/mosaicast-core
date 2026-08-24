// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;

/**
 * A plugin's tag assignment against one of its own subjects (ARCHITECTURE §6.1, SDK {@code Tags}).
 *
 * <p>Core cannot know what a wiki page is, so the subject is an opaque key the plugin invents inside its own
 * namespace — the property the schema store has for tables and {@code ctx.route.navigate} has for URLs.
 * Another plugin's subjects are not so much blocked as unnameable: {@code pluginId} is part of the key and
 * is taken from the caller's identity, never from the request.
 */
@Entity
@Table(name = "plugin_tag")
public class PluginTag {

    @EmbeddedId
    private Key id;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    protected PluginTag() {
        // for JPA
    }

    public PluginTag(String pluginId, String subjectKey, String tag) {
        this.id = new Key(pluginId, subjectKey, tag);
        this.createdAt = java.time.Instant.now();
    }

    public String getPluginId() {
        return id.pluginId();
    }

    public String getSubjectKey() {
        return id.subjectKey();
    }

    public String getTag() {
        return id.tag();
    }

    /** Composite primary key for {@link PluginTag}. */
    @Embeddable
    public record Key(
            @Column(name = "plugin_id") String pluginId,
            @Column(name = "subject_key") String subjectKey,
            @Column(name = "tag") String tag) implements Serializable {
    }
}
