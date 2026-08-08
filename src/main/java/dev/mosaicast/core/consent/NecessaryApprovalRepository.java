// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Admin approvals of plugin {@code necessary} claims (§12.5). */
public interface NecessaryApprovalRepository extends JpaRepository<NecessaryApproval, NecessaryApprovalKey> {

    List<NecessaryApproval> findByIdPluginId(String pluginId);
}
