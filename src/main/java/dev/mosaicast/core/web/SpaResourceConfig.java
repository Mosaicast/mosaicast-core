// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React/Vite shell and makes client-side deep links resolve on reload
 * (ARCHITECTURE §6.4).
 *
 * <p>Real static files (the JS/CSS bundle, brand assets, favicon) are served as-is; any other
 * navigation path falls back to {@code index.html} so the SPA router can take over. Backend paths
 * ({@code /api}, {@code /actuator}, {@code /branding}, {@code /plugins}) are never rewritten — they keep
 * their own handlers and 404s. Real HTTP 404s for unknown episode/route deep links are tightened in M6.
 */
@Component
public class SpaResourceConfig implements WebMvcConfigurer {

    private static final Resource INDEX = new ClassPathResource("/static/index.html");

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location)
                            throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Let backend namespaces 404 through their own handlers, never the SPA shell.
                        if (isBackendPath(resourcePath)) {
                            return null;
                        }
                        // Client-side route → hand the SPA its entry point.
                        return INDEX;
                    }
                });
    }

    private static boolean isBackendPath(String path) {
        return path.startsWith("api/")
                || path.startsWith("actuator/")
                || path.startsWith("branding/")
                || path.startsWith("plugins/");
    }
}
