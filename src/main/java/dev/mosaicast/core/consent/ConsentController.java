// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.consent.ConsentService.AuditView;
import dev.mosaicast.core.consent.ConsentService.ConsentView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consent endpoints (ARCHITECTURE §12.5).
 *
 * <p>{@code /api/consent} is the visitor's: the decisions on offer, what each service stores and who operates
 * it, what the core itself stores, and a fingerprint of the whole declaration so a stored answer can be told
 * apart from an answer to a different question. Anonymous by necessity — a consent surface that needs a login
 * is not a consent surface. An empty {@code categories} list means the site stays banner-free.
 *
 * <p>{@code /api/admin/consent} is the operator's, ADMIN via the {@code /api/admin/**} rule. It carries the
 * one thing the public payload deliberately omits — which plugin declared what — plus the resulting CSP
 * allow-list, so "why is this origin allowed?" has an answer that does not involve reading manifests on disk.
 */
@RestController
public class ConsentController {

    private final ConsentService consent;

    public ConsentController(ConsentService consent) {
        this.consent = consent;
    }

    @GetMapping("/api/consent")
    public ConsentView current() {
        return consent.current();
    }

    @GetMapping("/api/admin/consent")
    public AuditView audit() {
        return consent.audit();
    }

    /**
     * Approves a plugin's claim that one of its services is strictly necessary (§12.5).
     *
     * <p>Approving means the operator accepts that this service loads for every visitor without being asked,
     * and that they are content to defend that position — which is a judgement about their jurisdiction and
     * their deployment, not something a plugin author elsewhere can make for them. Until it happens, the
     * service is prompted like any other.
     *
     * <p>The approval covers the claim as it currently reads. A plugin update that adds an origin or a cookie
     * invalidates it, and the service goes back to being prompted until someone looks again.
     */
    @PostMapping("/api/admin/consent/necessary/{pluginId}/{serviceKey}")
    public AuditView approveNecessary(@PathVariable String pluginId, @PathVariable String serviceKey) {
        consent.approveNecessary(pluginId, serviceKey);
        return consent.audit();
    }

    /** Withdraws that approval; the service is prompted again from the next request. Idempotent. */
    @DeleteMapping("/api/admin/consent/necessary/{pluginId}/{serviceKey}")
    public AuditView revokeNecessary(@PathVariable String pluginId, @PathVariable String serviceKey) {
        consent.revokeNecessary(pluginId, serviceKey);
        return consent.audit();
    }
}
