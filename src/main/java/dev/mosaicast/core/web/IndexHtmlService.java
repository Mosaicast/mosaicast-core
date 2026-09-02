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
     * <p>The tags beyond title/description/image exist because a link is pasted into a messenger far more
     * often than into a search box, and the messengers are the strictest readers: WhatsApp and Telegram
     * render a card from these tags alone, with no JS and no second request. {@code og:type} lets an episode
     * announce itself as something other than a generic website, and {@code og:audio} lets a client that
     * knows what to do with audio (Telegram, Discord) offer it.
     *
     * @param title       the page title; never blank
     * @param description a one-line summary, possibly empty
     * @param imageUrl    an absolute or root-relative image URL, or {@code null} to send none
     * @param type        the {@code og:type} for this view — {@code website} for a listing, {@code article}
     *                    for one episode; never blank
     * @param audioUrl    the episode's audio URL, or {@code null} when this view has none
     * @param audioMime   the audio's MIME type when it can be told from the URL, else {@code null}
     * @param publishedAt when this was published, for {@code article:published_time}; {@code null} for none
     */
    public record Meta(String title, String description, String imageUrl, String type, String audioUrl,
                       String audioMime, java.time.Instant publishedAt) {

        /** The ordinary page: a listing or a static page, with nothing to say about audio or a date. */
        public Meta(String title, String description, String imageUrl) {
            this(title, description, imageUrl, "website", null, null, null);
        }
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
        return render(view, null);
    }

    /**
     * The same, rendered as a particular UI language (§6.4/§12.7).
     *
     * <p>{@code locale} is what {@code ?lang=} resolved to, already validated by {@code LocaleRegistry};
     * {@code null} means the site default. It is a render input rather than part of {@link PageView}
     * because it is a property of the <em>request</em>, not of the view: the same episode is one page,
     * described in whichever language was asked for.
     *
     * <p>It reaches two things a crawler reads and a visitor's browser does not: the document's
     * {@code lang} attribute and {@code og:locale}. Both were fixed strings before — {@code lang="en"} is
     * baked into the built {@code index.html}, and {@code og:locale} was the install's default on every
     * page — so a German page announced itself as English to everything that does not run JS.
     */
    public String render(PageView view, String locale) {
        // Null resolves to the install's default rather than to "leave it alone": the built shell says
        // lang="en" whatever the site is, so a German-default install was mislabelled on every page long
        // before any `?lang=` existed.
        String resolved = locale == null || locale.isBlank()
                ? siteConfig.get().getDefaultLocale() : locale.trim();
        String html = withLangAttribute(index(), resolved);
        int headEnd = html.indexOf(HEAD_END);
        if (headEnd < 0) {
            // No head to inject into (a stripped or unexpected build) — serve the shell unchanged rather
            // than corrupting it; the page still works, only the preview is generic.
            return html;
        }
        String withHead = html.substring(0, headEnd) + headTags(view, resolved) + html.substring(headEnd);
        return injectContent(withHead, view.noJsHtml());
    }

    /**
     * Rewrites the shell's {@code <html lang="…">} to the language actually being served.
     *
     * <p>The attribute is a build artifact — Vite emits whatever {@code frontend/index.html} says, which is
     * {@code en} — so it cannot be right for a site whose default is German, let alone for a per-request
     * language. It matters beyond tidiness: it is what a screen reader picks a voice from and what a
     * translation prompt keys on, and both are wrong in the same direction as the OG tags were.
     *
     * <p>Matched narrowly on the opening tag rather than by parsing: a shell without one is served
     * unchanged, which is the same failure posture the head and mount-point injections take.
     */
    private String withLangAttribute(String html, String locale) {
        String tag = locale == null || locale.isBlank() ? null : locale.trim();
        if (tag == null) {
            return html;
        }
        int open = html.indexOf("<html ");
        int close = open < 0 ? -1 : html.indexOf('>', open);
        if (open < 0 || close < 0) {
            return html;
        }
        String opening = html.substring(open, close);
        if (!opening.contains("lang=\"")) {
            return html;
        }
        return html.substring(0, open)
                + opening.replaceFirst("lang=\"[^\"]*\"", "lang=\"" + escape(tag) + "\"")
                + html.substring(close);
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

    /**
     * Everything this view contributes to {@code <head>}.
     *
     * <p><strong>{@code rel=canonical} and {@code og:url} are deliberately allowed to differ</strong>
     * (§6.4). They answer different questions: canonical says which URL a search engine should index — the
     * bare episode, because a timestamp is a position within one page and not a page of its own — while
     * {@code og:url} is the identity of the thing being shared. Emitting the canonical form as
     * {@code og:url} would let a scraper normalize a shared moment back to the top of the episode, which is
     * precisely the link someone did not send. {@link PageView#shareUrl()} falls back to the canonical URL,
     * so every view that has nothing extra to say still emits the two identically.
     */
    private String headTags(PageView view, String locale) {
        StringBuilder tags = new StringBuilder(ogTags(view.meta(), locale));
        if (view.canonicalUrl() != null && !view.canonicalUrl().isBlank()) {
            tags.append("    <link rel=\"canonical\" href=\"")
                    .append(escape(view.canonicalUrl())).append("\" />\n");
            tags.append(meta("og:url", view.shareUrl()));
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

    private String ogTags(Meta meta, String locale) {
        StringBuilder tags = new StringBuilder("\n");
        tags.append(meta("og:title", meta.title()));
        tags.append(meta("og:type", meta.type() == null || meta.type().isBlank() ? "website" : meta.type()));
        tags.append(meta("twitter:card", meta.imageUrl() == null ? "summary" : "summary_large_image"));
        tags.append(meta("twitter:title", meta.title()));
        // Site name and locale are properties of the install rather than of a URL, so they are read here
        // instead of being carried on every Meta a resolver builds.
        String siteName = siteConfig.get().getSiteName();
        if (siteName != null && !siteName.isBlank()) {
            tags.append(meta("og:site_name", siteName));
        }
        // The language this response is actually in, not the install's default. They agree on most
        // requests and differ on exactly the ones this tag exists to describe.
        String ogLocale = ogLocale(locale);
        if (ogLocale != null) {
            tags.append(meta("og:locale", ogLocale));
        }
        if (meta.description() != null && !meta.description().isBlank()) {
            tags.append(meta("og:description", meta.description()));
            tags.append(meta("twitter:description", meta.description()));
        }
        if (meta.imageUrl() != null && !meta.imageUrl().isBlank()) {
            tags.append(meta("og:image", meta.imageUrl()));
            tags.append(meta("twitter:image", meta.imageUrl()));
            // The title is the honest alt text: the image is the episode's or the show's artwork, and what
            // it depicts is not knowable from a feed. An empty alt would tell a screen reader nothing.
            tags.append(meta("og:image:alt", meta.title()));
            tags.append(meta("twitter:image:alt", meta.title()));
        }
        if (meta.audioUrl() != null && !meta.audioUrl().isBlank()) {
            tags.append(meta("og:audio", meta.audioUrl()));
            if (meta.audioMime() != null) {
                tags.append(meta("og:audio:type", meta.audioMime()));
            }
        }
        if (meta.publishedAt() != null) {
            tags.append(meta("article:published_time",
                    java.time.format.DateTimeFormatter.ISO_INSTANT.format(meta.publishedAt())));
        }
        return tags.toString();
    }

    /**
     * OpenGraph wants {@code language_TERRITORY}, while the site stores a plain language tag like
     * {@code de} (§12.7) — a bare language is not a valid {@code og:locale}.
     *
     * <p>A territory cannot be derived from a language in general, so the two launch locales get the pairing
     * they actually have and anything else is left to say only what it knows. Guessing wrongly here is worse
     * than saying nothing: {@code en_EN} is not a locale, and a scraper that cannot read the value is in the
     * same position as one that never received it.
     */
    private static String ogLocale(String locale) {
        String tag = locale == null || locale.isBlank() ? "en" : locale.trim().replace('-', '_');
        if (tag.contains("_")) {
            return tag;
        }
        return switch (tag.toLowerCase(java.util.Locale.ROOT)) {
            case "en" -> "en_US";
            case "de" -> "de_DE";
            default -> null;
        };
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
