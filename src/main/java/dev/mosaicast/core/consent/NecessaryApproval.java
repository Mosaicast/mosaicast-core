// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An admin's approval of a plugin's claim that one of its services is strictly necessary (§12.5).
 *
 * <p>A row means approved. There is no {@code approved} boolean, because a withdrawn approval is a deleted
 * row — the same shape as {@link dev.mosaicast.core.plugin.PluginActivation}, and for the same reason: the
 * default must need no bookkeeping, and here the default is "ask the visitor".
 *
 * <p>{@link #claimDigest} is what makes the approval mean something specific. It covers the service's origins
 * and the storage it declares, so a plugin update that adds a host or a cookie no longer matches what was
 * approved and the service returns to being prompted. Otherwise approving a plugin once would be a standing
 * permission for whatever it declares in its next version.
 */
@Entity
@Table(name = "plugin_consent_approval")
public class NecessaryApproval {

    @EmbeddedId
    private NecessaryApprovalKey id;

    @Column(name = "claim_digest", nullable = false)
    private String claimDigest;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt = Instant.now();

    protected NecessaryApproval() {
        // for JPA
    }

    public NecessaryApproval(NecessaryApprovalKey id, String claimDigest) {
        this.id = id;
        this.claimDigest = claimDigest;
    }

    /** Re-approves after the claim changed, recording what the admin saw this time. */
    public void reapprove(String claimDigest) {
        this.claimDigest = claimDigest;
        this.approvedAt = Instant.now();
    }

    public NecessaryApprovalKey getId() {
        return id;
    }

    public String getClaimDigest() {
        return claimDigest;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
