// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the per-plugin record of an account deletion. */
public interface UserDataErasureRepository extends JpaRepository<UserDataErasure, UUID> {

    Optional<UserDataErasure> findByUserIdAndPluginId(UUID userId, String pluginId);

    /** Everything not yet done — what the retry sweep replays and what admin shows. */
    @Query("select e from UserDataErasure e where e.status <> 'DONE' order by e.createdAt asc")
    List<UserDataErasure> findOpen();

    /** The open debts of one plugin — replayed when an operator switches it back on. */
    @Query("select e from UserDataErasure e where e.pluginId = :pluginId and e.status <> 'DONE'")
    List<UserDataErasure> findOpenFor(@Param("pluginId") String pluginId);
}
