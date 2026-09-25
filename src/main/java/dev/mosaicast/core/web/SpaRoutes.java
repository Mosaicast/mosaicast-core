// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.Set;

/**
 * The top-level paths the shell's router actually claims (ARCHITECTURE §6.6).
 *
 * <p>It exists so an unmatched path can answer <strong>404</strong> instead of 200. The SPA fallback
 * served the shell for anything that was not a real file and did not start with a backend prefix, so
 * {@code /totally/unknown/route} answered 200 with a page that renders "Not found" — a soft 404, which a
 * crawler indexes as a valid page. The episode route has always got this right; this is the general case
 * (core#179).
 *
 * <p><strong>This is a second copy of something the router owns</strong>, and that is a real cost. It is
 * only a list of <em>first</em> segments, which is the part that changes rarely and that ARCHITECTURE
 * binds anyway — and {@code SpaRoutesMatchTheRouterTest} reads {@code frontend/src/App.tsx} and fails if
 * the two disagree, which is what keeps the copy honest rather than a comment asking people to remember.
 */
public final class SpaRoutes {

    /**
     * First path segments the shell renders something for.
     *
     * <p>The empty string is the root. {@code p} is the plugin namespace, whose own 404s are decided by
     * {@code PluginPageController} — it never reaches the fallback.
     */
    public static final Set<String> KNOWN_TOP_LEVEL = Set.of(
            "", "feeds", "episodes", "legal", "cookies", "about", "search", "notifications",
            "account", "admin", "p");

    private SpaRoutes() {
    }

    /** Whether the shell claims this request path — {@code /feeds/anything} does, {@code /nonsense} does not. */
    public static boolean claims(String path) {
        String trimmed = path == null ? "" : path;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int slash = trimmed.indexOf('/');
        String first = slash < 0 ? trimmed : trimmed.substring(0, slash);
        return KNOWN_TOP_LEVEL.contains(first);
    }
}
