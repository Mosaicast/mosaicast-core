// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes core build metadata for the shell (and a future in-app "info" tab). The version is the single
 * source of truth from {@code gradle.properties}, filtered into {@code build-metadata.properties} at
 * build time — clients read it here instead of it being baked separately into the frontend.
 */
@RestController
@PropertySource("classpath:build-metadata.properties")
public class MetaController {

    private final String version;
    private final boolean devLoginEnabled;

    public MetaController(@Value("${mosaicast.version:dev}") String version, Environment environment) {
        this.version = version;
        // The dev-login bypass exists only under the dev profile (DevLoginController is @Profile("dev")),
        // so the shell shows that login option only when it will actually work (§8).
        this.devLoginEnabled = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /**
     * What the shell needs before anybody logs in.
     *
     * <p>The version stays here, and stays anonymous, against the audit's suggestion to drop it (core#187).
     * It is not free to remove: the footer renders it on every page for every visitor, which for an AGPL
     * application whose source is linked two lines away is a feature rather than a leak. An anonymous scan
     * learns which CVEs might apply — from a repository that would have told it anyway. Obscuring a version
     * that the licence obliges this project to publish is theatre with a visible cost, so the actual
     * exposure is dealt with where it lives: {@code /actuator/**} is ADMIN-only now.
     */
    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        return Map.of("name", "Mosaicast", "version", version, "devLoginEnabled", devLoginEnabled);
    }
}
