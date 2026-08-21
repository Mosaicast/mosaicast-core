// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shell's navigation menu (ARCHITECTURE §7.3).
 *
 * <p>The public endpoint sits under {@code /api/plugins/} so it inherits the anonymous read rule the rest of
 * the plugin surface already has, rather than needing its own security entry. What it exposes — which pages
 * this install offers a visitor — is the same class of information as the public manifest.
 *
 * <p>The response is already filtered to the caller: the host decides access, and an anonymous visitor is
 * never told that a podcaster-only entrance exists.
 */
@RestController
public class PluginNavController {

    private final PluginNavService nav;

    public PluginNavController(PluginNavService nav) {
        this.nav = nav;
    }

    /** The entries this caller may see, enabled, in order. */
    @GetMapping("/api/plugins/navigation")
    public List<PluginNavService.NavItem> navigation(Authentication authentication) {
        return nav.visibleTo(CurrentUser.role(authentication));
    }

    /** Every declared entry with the decision in force — including ones currently switched off. */
    @GetMapping("/api/admin/navigation")
    public List<PluginNavService.AdminNavItem> adminNavigation() {
        return nav.adminList();
    }

    /**
     * Replaces the admin's decisions.
     *
     * <p>Whole-list rather than per-row: an ordering is only meaningful as a set, and saving one row at a
     * time would let two entries settle on the same position with nothing to say which the admin meant.
     */
    @PutMapping("/api/admin/navigation")
    public ResponseEntity<List<PluginNavService.AdminNavItem>> updateNavigation(
            @RequestBody List<PluginNavService.NavDecision> decisions) {
        nav.apply(decisions == null ? List.of() : decisions);
        return ResponseEntity.ok(nav.adminList());
    }
}
