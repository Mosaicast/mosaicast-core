// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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

    /**
     * Marks an unclaimed path as a 404 while still letting the shell render.
     *
     * <p>Set before the resource handler writes anything, because by the time the body is going out the
     * status line is already decided. The shell is still served — the SPA boots and shows its own
     * not-found view, which is a better page than a bare error — but the status is honest, so a crawler
     * stops indexing arbitrary junk URLs as valid pages (core#179).
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response, @NonNull Object handler) {
                String path = request.getRequestURI();
                if (!SpaRoutes.claims(path) && !isBackendPath(stripLeadingSlash(path))
                        && !hasFileExtension(path)) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                }
                return true;
            }
        });
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        // The content-hashed build output, which is immutable by construction: `index-BMksdjBz.js` cannot
        // change without changing its name. It was being served `no-cache, no-store, must-revalidate` —
        // Spring Security stamps that on any response that does not already carry a Cache-Control, which is
        // the right default and the wrong answer here, so a 462 KB bundle was re-downloaded on every load
        // (core#187). Declared before the catch-all below so it wins for /assets/**, and set here rather
        // than by disabling the framework default, which would take `no-store` off the API responses too.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

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

    /** Strips one leading slash, so a request URI can be compared with the resource-relative prefixes. */
    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Whether the last segment looks like a file rather than a route.
     *
     * <p>Static assets under {@code /assets/} are content-hashed and always carry an extension, and so do
     * the brand files and {@code theme-init.js}. A missing one of those should 404 on its own merits —
     * which it does, because the resolver below returns null for a backend path and the shell for
     * everything else — but it should never be *marked* 404 here on the way in, or a file that does exist
     * would be served with the wrong status.
     */
    private static boolean hasFileExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1;
    }

    private static boolean isBackendPath(String path) {
        return path.startsWith("api/")
                || path.startsWith("actuator/")
                || path.startsWith("branding/")
                || path.startsWith("plugins/");
    }
}
