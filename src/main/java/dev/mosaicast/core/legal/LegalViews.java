// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

/** Public legal-page views (ARCHITECTURE §12.6). */
public final class LegalViews {

    private LegalViews() {
    }

    /**
     * A footer link.
     *
     * @param slug the page slug ({@code /legal/<slug>})
     * @param title the page title in the resolved locale
     * @param role  the role marker ({@code privacy}/{@code imprint}/{@code terms}), or {@code null}
     */
    public record FooterEntry(String slug, String title, String role) {
    }

    /**
     * A rendered page.
     *
     * @param slug  the page slug
     * @param title the title in the resolved locale
     * @param role  the role marker, or {@code null}
     * @param html  the sanitized HTML body
     */
    public record RenderedPage(String slug, String title, String role, String html) {
    }
}
