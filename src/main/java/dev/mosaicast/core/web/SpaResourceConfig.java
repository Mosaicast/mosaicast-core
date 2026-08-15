// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.jspecify.annotations.NonNull;
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
 * their own handlers and 404s.
 *
 * <p>This is the <em>fallback</em>, not the whole story: the host's own public routes are mapped by
 * {@link ShellController} and plugin deep links by {@code PluginPageController}, both of which take
 * precedence and answer with per-URL metadata and a real 404 for an unknown slug (§6.6). What reaches here
 * is a path no controller claims — the shell's remaining client-side routes — and it is still served 200,
 * because the alternative is the router losing routes nobody told this class about.
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
