// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One log entry as the admin viewer sees it.
 *
 * @param id        stable id, also the paging tie-breaker
 * @param at        when it happened
 * @param level     ERROR | WARN | INFO | DEBUG
 * @param subsystem which part of the host produced it
 * @param source    the logger class, or {@code frontend} for a plugin-reported entry
 * @param pluginId  the plugin it is attributable to, or {@code null}
 * @param message   the one-line message
 * @param detail    stack trace or long form, shown when the row is expanded
 * @param context   structured extras
 */
public record AppLogView(Long id, Instant at, String level, String subsystem, String source,
                         String pluginId, String message, String detail, JsonNode context) {

    public static AppLogView of(AppLogEntry entry) {
        return new AppLogView(entry.getId(), entry.getAt(), entry.getLevel(), entry.getSubsystem(),
                entry.getSource(), entry.getPluginId(), entry.getMessage(), entry.getDetail(),
                entry.getContext());
    }
}
