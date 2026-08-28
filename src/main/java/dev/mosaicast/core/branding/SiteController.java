// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public site/branding payload and its admin editor (ARCHITECTURE §12.1). {@code GET /api/site} is
 * anonymous (the shell needs it at boot); editing is ADMIN-only (enforced by the {@code /api/admin/**}
 * rule in SecurityConfig).
 */
@RestController
public class SiteController {

    private final SiteConfigService site;

    public SiteController(SiteConfigService site) {
        this.site = site;
    }

    /**
     * Admin edit of name / accent / mode. Any omitted field is left unchanged.
     *
     * <p>The default language is <em>not</em> here: it moved to {@code PUT /api/admin/i18n} when languages
     * became a runtime registry (§12.7). It has to be validated against the languages content may be authored
     * in, and two endpoints writing one setting with only one of them checking it is how a site ends up with a
     * default nobody can write a legal page in.
     */
    public record UpdateSite(String siteName, String accentSeed, String modePolicy) {
    }

    @GetMapping("/api/site")
    public SiteView site() {
        return SiteView.of(site.get(), site.theme());
    }

    @PutMapping("/api/admin/site")
    public SiteView update(@RequestBody UpdateSite request) {
        ModePolicy mode = parseMode(request.modePolicy());
        site.update(request.siteName(), request.accentSeed(), mode, null);
        return SiteView.of(site.get(), site.theme());
    }

    private static ModePolicy parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ModePolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of light, dark, system");
        }
    }
}
