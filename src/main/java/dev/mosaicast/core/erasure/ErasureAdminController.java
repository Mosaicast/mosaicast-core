// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is still owed after an account deletion (ARCHITECTURE §12).
 *
 * <p>Admin-only, and it exists because the alternative to showing this is not showing it: an erasure a
 * plugin never completed is a legal obligation the operator is carrying without knowing. The rows name the
 * plugin and the reason, so the action — switch the plugin back on, or fix it — is legible.
 *
 * <p>No user names here, deliberately. The account is gone; what is left is an id a handler needs to find
 * its own rows, and re-attaching a person to it in an admin list would undo part of what the deletion was
 * for.
 */
@RestController
public class ErasureAdminController {

    private final AccountErasureService erasures;

    public ErasureAdminController(AccountErasureService erasures) {
        this.erasures = erasures;
    }

    @GetMapping("/api/admin/erasures")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ErasureView> outstanding() {
        return erasures.outstanding().stream().map(ErasureView::of).toList();
    }

    /** Runs the outstanding erasures now, rather than waiting for the hourly sweep. */
    @PostMapping("/api/admin/erasures/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ErasureView> retryNow() {
        for (UUID userId : erasures.outstanding().stream().map(UserDataErasure::getUserId).distinct().toList()) {
            erasures.runHandlers(userId);
        }
        return outstanding();
    }

    /** One outstanding erasure, as admin sees it. */
    public record ErasureView(UUID id, UUID userId, String pluginId, String status, int attempts,
                              String lastError, Instant createdAt, Instant updatedAt) {

        static ErasureView of(UserDataErasure erasure) {
            return new ErasureView(erasure.getId(), erasure.getUserId(), erasure.getPluginId(),
                    erasure.getStatus().name(), erasure.getAttempts(), erasure.getLastError(),
                    erasure.getCreatedAt(), erasure.getUpdatedAt());
        }
    }
}
