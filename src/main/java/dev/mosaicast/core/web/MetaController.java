// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
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

    public MetaController(@Value("${mosaicast.version:dev}") String version) {
        this.version = version;
    }

    @GetMapping("/api/meta")
    public Map<String, String> meta() {
        return Map.of("name", "Mosaicast", "version", version);
    }
}
