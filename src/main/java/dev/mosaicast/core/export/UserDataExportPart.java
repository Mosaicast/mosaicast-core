// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

/**
 * One plugin's part of one export (ARCHITECTURE §12.8.1), recorded <em>before</em> the plugin is asked — so a
 * plugin that throws, times out or cannot be asked at all is an outcome in {@code outcome.json} and in admin,
 * never a silent gap in the archive.
 */
@Entity
@Table(name = "user_data_export_part")
public class UserDataExportPart {

    /** What came of asking one plugin. The wire form is lower-case with dashes, as {@code outcome.json} has it. */
    public enum Outcome {
        /** Recorded, not yet asked — what a crash mid-export leaves behind, read as outstanding. */
        PENDING,
        /** The plugin handed its part over. */
        COMPLETE,
        /** The plugin was asked and holds nothing on this person. */
        EMPTY,
        /** The plugin threw, ran out of time, or handed over more than it may. */
        FAILED,
        /** The plugin could not be asked: switched off, or rejected with data it stored before. */
        OUTSTANDING,
        /** The plugin has no {@code UserDataHandler}: core cannot know it holds nothing, only that it did not say. */
        NOT_SUPPORTED;

        public String wireName() {
            return this == PENDING ? "outstanding" : name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "export_id", nullable = false, updatable = false)
    private UUID exportId;

    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(nullable = false)
    private String outcome;

    @Column
    private String detail;

    @Column
    private Long bytes;

    protected UserDataExportPart() {
        // for JPA
    }

    /** A plugin's part, recorded before it is asked. */
    public static UserDataExportPart pending(UUID exportId, String pluginId) {
        UserDataExportPart part = new UserDataExportPart();
        part.id = UUID.randomUUID();
        part.exportId = exportId;
        part.pluginId = pluginId;
        part.outcome = Outcome.PENDING.name();
        return part;
    }

    /** Records what came of asking. */
    public void settle(Outcome outcome, String detail, Long bytes) {
        this.outcome = outcome.name();
        this.detail = detail;
        this.bytes = bytes;
    }

    public UUID getExportId() {
        return exportId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public Outcome getOutcome() {
        return Outcome.valueOf(outcome);
    }

    public String getDetail() {
        return detail;
    }

    public Long getBytes() {
        return bytes;
    }
}
