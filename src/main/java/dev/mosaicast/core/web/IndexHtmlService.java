// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import dev.mosaicast.core.branding.SiteConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Serves the built shell's {@code index.html} with per-URL OpenGraph/Twitter tags injected server-side
 * (ARCHITECTURE §6.4). Link scrapers (WhatsApp, Discord, Facebook) do not run JS, so a shared URL has to
 * carry its meta tags in the HTML the server returns — the SPA replaces nothing a scraper would ever see.
 *
 * <p>This is the seam the rest of §6.4/§6.6 grows on: today it is called for plugin deep links, and the
 * per-scope resolvers (episode, feed, season, site), JSON-LD and the no-JS content block hang off the same
 * {@link Meta} shape rather than a second mechanism.
 */
@Service
public class IndexHtmlService {

    private static final Logger log = LoggerFactory.getLogger(IndexHtmlService.class);

    private static final Resource INDEX = new ClassPathResource("/static/index.html");
    private static final String HEAD_END = "</head>";

    /**
     * The <em>opening</em> tag of the SPA's mount point. The block is inserted after this, so it ends up
     * <strong>inside</strong> {@code #root} rather than beside it — see {@link #injectContent}.
     */
    private static final String ROOT_OPEN = "<div id=\"root\">";

    /** Stand-in for a build without the frontend bundle: enough of a document to carry meta tags. */
    private static final String MINIMAL_SHELL =
            "<!doctype html>\n<html lang=\"en\">\n  <head>\n    <meta charset=\"UTF-8\" />\n"
                    + "  </head>\n  <body>\n    <div id=\"root\"></div>\n  </body>\n</html>\n";

    private final SiteConfigService siteConfig;

    /** The built shell, read once — it is an immutable build artifact. */
    private volatile String cachedIndex;

    public IndexHtmlService(SiteConfigService siteConfig) {
        this.siteConfig = siteConfig;
    }

    /**
     * What a scraper should show for one URL.
     *
     * @param title       the page title; never blank
     * @param description a one-line summary, possibly empty
     * @param imageUrl    an absolute or root-relative image URL, or {@code null} to send none
     */
    public record Meta(String title, String description, String imageUrl) {
    }

    /** Site-level fallback: used when nothing more specific is known for a URL. */
    public Meta siteMeta() {
        String name = siteConfig.get().getSiteName();
        return new Meta(name == null || name.isBlank() ? "Mosaicast" : name, "", null);
    }

    /** The shell with {@code meta} injected into its {@code <head>}. */
    public String render(Meta meta) {
        return render(PageView.metaOnly(meta));
    }

    /**
     * The shell with everything the server knows about this URL injected: meta tags, canonical link, RSS
     * discovery, JSON-LD, and the no-JS content block (ARCHITECTURE §6.6).
     *
     * <p>Head content goes before {@code </head>}; the content block goes inside {@code #root}, which is the
     * element React mounts into — so the shell replaces it on mount with no extra hand-off, and a crawler
     * that never mounts anything reads it as the page.
     */
    public String render(PageView view) {
        String html = index();
        int headEnd = html.indexOf(HEAD_END);
        if (headEnd < 0) {
            // No head to inject into (a stripped or unexpected build) — serve the shell unchanged rather
            // than corrupting it; the page still works, only the preview is generic.
            return html;
        }
        String withHead = html.substring(0, headEnd) + headTags(view) + html.substring(headEnd);
        return injectContent(withHead, view.noJsHtml());
    }

    /**
     * Puts the no-JS block inside the SPA's mount point.
     *
     * <p>React's {@code createRoot(...).render(...)} replaces the container's children, so the block is gone
     * the moment the shell mounts and there is nothing to clean up by hand — and a crawler that runs no JS
     * keeps it. Injecting it as a sibling instead would have needed the shell to remove it, and any path
     * where that failed (an error before mount, a slow chunk) would show a visitor the page twice.
     */
    private static String injectContent(String html, String content) {
        if (content == null || content.isBlank()) {
            return html;
        }
        int rootAt = html.indexOf(ROOT_OPEN);
        if (rootAt < 0) {
            // An unexpected shell with no mount point: serve it unchanged rather than putting the block
            // somewhere React will not clear, which would show every visitor the page twice.
            return html;
        }
        int insertAt = rootAt + ROOT_OPEN.length();
        return html.substring(0, insertAt) + "\n" + content + html.substring(insertAt);
    }

    /** Everything this view contributes to {@code <head>}. */
    private String headTags(PageView view) {
        StringBuilder tags = new StringBuilder(ogTags(view.meta()));
        if (view.canonicalUrl() != null && !view.canonicalUrl().isBlank()) {
            tags.append("    <link rel=\"canonical\" href=\"")
                    .append(escape(view.canonicalUrl())).append("\" />\n");
            tags.append(meta("og:url", view.canonicalUrl()));
        }
        if (view.jsonLd() != null && !view.jsonLd().isBlank()) {
            tags.append("    <script type=\"application/ld+json\">")
                    .append(escapeInScript(view.jsonLd()))
                    .append("</script>\n");
        }
        return tags.toString();
    }

    /** The shell exactly as built, no injection. */
    public String plain() {
        return index();
    }

    private String ogTags(Meta meta) {
        StringBuilder tags = new StringBuilder("\n");
        tags.append(meta("og:title", meta.title()));
        tags.append(meta("og:type", "website"));
        tags.append(meta("twitter:card", meta.imageUrl() == null ? "summary" : "summary_large_image"));
        tags.append(meta("twitter:title", meta.title()));
        if (meta.description() != null && !meta.description().isBlank()) {
            tags.append(meta("og:description", meta.description()));
            tags.append(meta("twitter:description", meta.description()));
        }
        if (meta.imageUrl() != null && !meta.imageUrl().isBlank()) {
            tags.append(meta("og:image", meta.imageUrl()));
            tags.append(meta("twitter:image", meta.imageUrl()));
        }
        return tags.toString();
    }

    private static String meta(String property, String content) {
        // Plugin-provided text lands in an attribute: escape it, never trust a provider's string.
        return "    <meta property=\"%s\" content=\"%s\" />\n".formatted(property, escape(content));
    }

    /** HTML-escapes a value for an attribute or text node. Public so resolvers build content the same way. */
    public static String escape(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }

    /**
     * Makes a JSON string safe to sit inside a {@code <script>} element.
     *
     * <p>Ordinary HTML escaping is wrong here — the contents of a {@code script} element are not parsed as
     * HTML, so {@code &quot;} would land in the JSON literally and break it. What actually has to be
     * neutralized is the one sequence that can end the element early: an episode title or show-note
     * containing {@code </script>} would otherwise close the block and leave the rest of a podcaster's feed
     * content being parsed as markup. Escaping the {@code /} of every {@code </} keeps the JSON valid (JSON
     * reads {@code \/} as {@code /}) and leaves no way out of the element.
     */
    private static String escapeInScript(String json) {
        return json == null ? "" : json.replace("</", "<\\/");
    }

    private String index() {
        String cached = cachedIndex;
        if (cached != null) {
            return cached;
        }
        try {
            cached = new String(INDEX.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The shell is a build artifact of the frontend, absent in a backend-only build or test run.
            // A missing bundle must not turn a shareable URL into a 500: serve a minimal document that
            // still carries the meta tags a scraper came for.
            log.warn("No built shell at static/index.html; serving a minimal document instead");
            cached = MINIMAL_SHELL;
        }
        cachedIndex = cached;
        return cached;
    }
}
