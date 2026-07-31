// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry in the host's operational log (ARCHITECTURE §13). Written either by the Logback appender — so
 * every existing and future {@code log.warn} in core shows up without touching its call site — or explicitly
 * for state changes and plugin-reported entries.
 *
 * <p>{@code level} and {@code subsystem} are stored as plain text rather than enums: the set of subsystems
 * grows with the codebase, and a value the current build does not know must still be readable rather than
 * blow up deserialization of an old row.
 */
@Entity
@Table(name = "app_log")
public class AppLogEntry {

    /** Hard caps, enforced on the way in so one runaway caller cannot bloat the table or the viewer. */
    public static final int MAX_MESSAGE = 2_000;
    public static final int MAX_DETAIL = 8_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at = Instant.now();

    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private String subsystem;

    @Column
    private String source;

    @Column(name = "plugin_id")
    private String pluginId;

    @Column(nullable = false)
    private String message;

    @Column
    private String detail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode context;

    protected AppLogEntry() {
        // for JPA
    }

    public AppLogEntry(Instant at, AppLogLevel level, String subsystem, String source, String pluginId,
                       String message, String detail, JsonNode context) {
        this.at = at == null ? Instant.now() : at;
        this.level = level.name();
        this.subsystem = subsystem;
        this.source = source;
        this.pluginId = pluginId;
        this.message = truncate(message, MAX_MESSAGE);
        this.detail = truncate(detail, MAX_DETAIL);
        this.context = context;
    }

    /** Keeps an oversized message readable instead of rejecting it — a truncated entry still diagnoses. */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public String getLevel() {
        return level;
    }

    public String getSubsystem() {
        return subsystem;
    }

    public String getSource() {
        return source;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getMessage() {
        return message;
    }

    public String getDetail() {
        return detail;
    }

    public JsonNode getContext() {
        return context;
    }
}
