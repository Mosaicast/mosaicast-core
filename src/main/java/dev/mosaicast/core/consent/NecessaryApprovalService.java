// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.plugin.PluginManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and checks admin approval of plugin {@code necessary} claims (ARCHITECTURE §12.5).
 *
 * <p>A service declared {@code "category": "necessary"} is never offered to the visitor and its origins go
 * into every visitor's CSP unconditionally. That is the correct treatment for something genuinely strictly
 * necessary, and an open door for anything else: the only check was that the category string was non-blank,
 * so a tracker could declare itself necessary and load for everyone, unprompted and unrefusable.
 *
 * <p>Whether a given third-party service is strictly necessary is a legal judgement about a specific
 * deployment, not a fact a plugin author can assert from another country. So the host treats the claim as a
 * proposal and the operator decides. Until they do, the service is prompted like any other.
 */
@Service
public class NecessaryApprovalService {

    private static final Logger log = LoggerFactory.getLogger(NecessaryApprovalService.class);

    private final NecessaryApprovalRepository approvals;

    public NecessaryApprovalService(NecessaryApprovalRepository approvals) {
        this.approvals = approvals;
    }

    /**
     * Whether this exact claim has been approved.
     *
     * <p>"Exact" is the point: the stored digest covers the origins and storage that were on screen when the
     * admin clicked. A plugin update that adds a host or a cookie no longer matches, and the service reverts
     * to prompted until someone looks again. An approval is for a claim, not for a plugin.
     */
    @Transactional(readOnly = true)
    public boolean isApproved(String pluginId, PluginManifest.Service service) {
        return approvals.findById(new NecessaryApprovalKey(pluginId, serviceKey(service)))
                .map(approval -> approval.getClaimDigest().equals(claimDigest(service)))
                .orElse(false);
    }

    /** Approves the claim as it stands right now. */
    @Transactional
    public void approve(String pluginId, PluginManifest.Service service) {
        NecessaryApprovalKey key = new NecessaryApprovalKey(pluginId, serviceKey(service));
        String digest = claimDigest(service);
        approvals.findById(key).ifPresentOrElse(
                existing -> existing.reapprove(digest),
                () -> approvals.save(new NecessaryApproval(key, digest)));
        log.info("Consent: admin approved '{}' service '{}' as strictly necessary — its origins now load for "
                + "every visitor without being asked", pluginId, serviceKey(service));
    }

    /** Withdraws approval; the service goes back to being prompted. Idempotent. */
    @Transactional
    public void revoke(String pluginId, String serviceKey) {
        NecessaryApprovalKey key = new NecessaryApprovalKey(pluginId, serviceKey);
        if (approvals.existsById(key)) {
            approvals.deleteById(key);
            log.info("Consent: admin revoked the necessary approval for '{}' service '{}' — it is prompted "
                    + "again", pluginId, serviceKey);
        }
    }

    /** When the claim was approved, if it currently is. */
    @Transactional(readOnly = true)
    public Optional<java.time.Instant> approvedAt(String pluginId, PluginManifest.Service service) {
        return approvals.findById(new NecessaryApprovalKey(pluginId, serviceKey(service)))
                .filter(approval -> approval.getClaimDigest().equals(claimDigest(service)))
                .map(NecessaryApproval::getApprovedAt);
    }

    /**
     * The stable key for a service within its plugin: its declared id, or its name when it declares none.
     *
     * <p>{@code id} is optional in the manifest, so it cannot be relied on alone. Falling back to the name
     * means renaming a service also drops its approval, which is the safe direction.
     */
    public static String serviceKey(PluginManifest.Service service) {
        if (service.id() != null && !service.id().isBlank()) {
            return service.id().trim();
        }
        return service.name() == null ? "" : service.name().trim();
    }

    /**
     * What was approved: the origins the CSP would be widened by, and the storage the service places on the
     * device. Deliberately <em>not</em> the whole declaration — a corrected typo in a purpose sentence should
     * not silently un-approve a service, while a new tracking host must.
     */
    static String claimDigest(PluginManifest.Service service) {
        StringBuilder claim = new StringBuilder();
        service.hostsOrEmpty().stream()
                .filter(host -> host != null && !host.isBlank())
                .map(String::trim)
                .sorted()
                .forEach(host -> claim.append(host).append('\n'));
        claim.append("--\n");
        service.storageOrEmpty().stream()
                .map(item -> item.name() + "\t" + item.type())
                .sorted()
                .forEach(item -> claim.append(item).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(claim.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
