// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import java.util.UUID;

/**
 * A data export was requested (ARCHITECTURE §12.8.1). Published inside the request's transaction and built only
 * after it commits, so the job never runs for a row that rolled back.
 *
 * @param exportId the export to build
 */
public record ExportRequestedEvent(UUID exportId) {
}
