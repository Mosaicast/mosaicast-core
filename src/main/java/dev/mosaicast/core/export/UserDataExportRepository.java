// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data exports (ARCHITECTURE §12.8.1). */
public interface UserDataExportRepository extends JpaRepository<UserDataExport, UUID> {

    /** A person's newest export, whatever its state. */
    Optional<UserDataExport> findFirstByUserIdOrderByRequestedAtDesc(UUID userId);

    /** A person's newest export in any state but {@code status} — a failed one does not count against the interval. */
    Optional<UserDataExport> findFirstByUserIdAndStatusNotOrderByRequestedAtDesc(UUID userId,
                                                                                 UserDataExport.Status status);

    /** Every export of one person — what erasure deletes, archives included. */
    List<UserDataExport> findByUserId(UUID userId);

    /** Ready archives past their retention. */
    List<UserDataExport> findByStatusAndExpiresAtBefore(UserDataExport.Status status, Instant now);

    /** Exports still running since before {@code cutoff} — interrupted, since a run takes minutes at most. */
    List<UserDataExport> findByStatusAndRequestedAtBefore(UserDataExport.Status status, Instant cutoff);

    /** The admin view: newest first. */
    List<UserDataExport> findAllByOrderByRequestedAtDesc(Pageable pageable);
}
