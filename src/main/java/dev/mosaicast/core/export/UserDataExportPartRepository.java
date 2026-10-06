// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-plugin outcomes of data exports (ARCHITECTURE §12.8.1). */
public interface UserDataExportPartRepository extends JpaRepository<UserDataExportPart, UUID> {

    /** One export's parts, in plugin order. */
    List<UserDataExportPart> findByExportIdOrderByPluginIdAsc(UUID exportId);

    /** The parts of several exports, for the admin view. */
    List<UserDataExportPart> findByExportIdIn(Collection<UUID> exportIds);
}
