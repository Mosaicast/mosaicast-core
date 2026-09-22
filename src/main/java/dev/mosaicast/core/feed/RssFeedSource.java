// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rometools.modules.itunes.EntryInformation;
import com.rometools.modules.itunes.FeedInformation;
import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import dev.mosaicast.plugin.api.Access;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * An RSS/Atom {@link FeedSource} backed by Rome (ARCHITECTURE §5.1). Provides audio (enclosures) and
 * seasons ({@code itunes:season}); it cannot gate tiers and is not push-based.
 *
 * <p>Polling uses HTTP conditional GET (§5.4): the last-seen {@code ETag}/{@code Last-Modified} are sent
 * as {@code If-None-Match}/{@code If-Modified-Since} so an unchanged feed returns a cheap 304.
 */
@Component
public class RssFeedSource implements FeedSource {

    private static final Logger log = LoggerFactory.getLogger(RssFeedSource.class);

    private static final SourceCapabilities CAPABILITIES =
            new SourceCapabilities(true, false, true, false);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * A hard wall-clock ceiling on the whole exchange, body included.
     *
     * <p>{@code HttpRequest.timeout} bounds the wait for a <em>response</em>, which a host that answers
     * promptly and then dribbles one {@code <item>} per second satisfies forever. The audit held a request
     * thread open past 75 seconds that way. Only a budget over the complete exchange closes it, which is why
     * the send below is async and joined with a timeout rather than blocking.
     */
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The most feed body that will be read, in bytes.
     *
     * <p>Rome builds a full JDOM tree over whatever arrives, several times the byte size in heap, and
     * {@code FeedScheduler} polls every due feed on one thread — so one oversized feed is the whole instance.
     * The audit streamed 200 MB through and had all of it parsed. 16 MB is far above any real podcast feed:
     * a decade-long weekly show with generous show notes is a low single-digit number of megabytes.
     */
    static final long MAX_BODY_BYTES = 16L * 1024 * 1024;

    /**
     * The most items one feed body may contribute.
     *
     * <p>The byte cap alone does not bound the work: 16 MB of minimal {@code <item>} elements is well over a
     * hundred thousand of them, and {@link Reconciler} spends several statements per item inside one
     * transaction (a ref upsert, a snapshot upsert, a tag wipe and a vocabulary lookup per keyword). That is
     * a single feed holding the poll thread and a database connection for minutes. 20,000 is two orders of
     * magnitude above a real show — a weekly podcast reaches 1,000 items after nineteen years.
     */
    static final int MAX_ITEMS = 20_000;

    /** Redirect hops followed before giving up. Enough for the usual CDN/canonical-host shuffle, not a loop. */
    private static final int MAX_REDIRECTS = 5;

    private static final String USER_AGENT = "Mosaicast/1.0 (+https://github.com/mosaicast)";

    private final HttpClient http;
    private final OutboundTargetPolicy targets;

    public RssFeedSource(OutboundTargetPolicy targets) {
        this.targets = targets;
        this.http = HttpClient.newBuilder()
                // NEVER, not NORMAL: the redirect has to be re-checked against the target policy on every hop.
                // NORMAL refuses only an HTTPS→HTTP downgrade, so a URL that starts on http:// is followed
                // anywhere at all — which is how an attacker-controlled public host 302s into 127.0.0.1.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String type() {
        return Feed.TYPE_RSS;
    }

    @Override
    public SourceCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public FetchResult fetch(SourceConfig cfg) throws FetchException {
        HttpResponse<byte[]> response = get(cfg);
        int status = response.statusCode();
        if (status == 304) {
            return FetchResult.notModified();
        }
        if (status != 200) {
            throw new FetchException("Feed fetch failed with HTTP " + status + " for " + cfg.url());
        }
        requireFeedLikeBody(response, cfg.url());
        ParsedFeed parsed = parse(response.body(), cfg.url());
        String etag = response.headers().firstValue("ETag").orElse(null);
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
        return FetchResult.changed(parsed.episodes(), etag, lastModified, parsed.feedTitle(),
                parsed.feedImageUrl(), parsed.feedAuthor(), parsed.feedDescription());
    }

    /**
     * Fetches the feed, following redirects by hand so every hop is re-validated before it is dereferenced.
     *
     * <p>The deadline is shared across the whole chain rather than granted afresh per hop, so a redirect loop
     * cannot buy more time than a single request would have.
     */
    private HttpResponse<byte[]> get(SourceConfig cfg) throws FetchException {
        // As a FetchException, not the policy's IllegalArgumentException: a stored feed whose host starts
        // resolving somewhere private (a DNS change, a provider moving a service) is a feed that now fails,
        // and FeedPipeline should record it and back off like any other failure rather than let a runtime
        // exception escape to the scheduler's catch-all.
        URI uri;
        try {
            uri = targets.validate(cfg.url());
        } catch (IllegalArgumentException e) {
            throw new FetchException(e.getMessage(), e);
        }
        long deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();

        for (int hop = 0; ; hop++) {
            HttpResponse<byte[]> response = send(uri, cfg, deadline);
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            if (hop >= MAX_REDIRECTS) {
                throw new FetchException("Feed fetch failed: too many redirects for " + cfg.url());
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new FetchException("Feed fetch failed: redirect without a Location"));
            try {
                // Resolve relative to the hop we are on, then re-run the full policy — scheme, userinfo and
                // address included. This is the check `Redirect.NORMAL` does not do.
                uri = targets.validate(uri.resolve(location));
            } catch (IllegalArgumentException e) {
                throw new FetchException(e.getMessage(), e);
            }
        }
    }

    private HttpResponse<byte[]> send(URI uri, SourceConfig cfg, long deadline) throws FetchException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new FetchException("Feed fetch timed out for " + cfg.url());
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .timeout(Duration.ofNanos(remaining))
                .GET();
        if (cfg.etag() != null) {
            req.header("If-None-Match", cfg.etag());
        }
        if (cfg.lastModified() != null) {
            req.header("If-Modified-Since", cfg.lastModified());
        }

        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(req.build(), LimitedBodyHandler.of(MAX_BODY_BYTES));
        try {
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new FetchException("Feed fetch timed out for " + cfg.url(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BodyTooLargeException) {
                throw new FetchException("Feed body exceeds " + MAX_BODY_BYTES + " bytes for " + cfg.url(), cause);
            }
            throw new FetchException("Feed fetch I/O error for " + cfg.url(), cause);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new FetchException("Feed fetch interrupted for " + cfg.url(), e);
        }
    }

    /** Parsed feed: the channel metadata plus its items. */
    record ParsedFeed(
            String feedTitle, String feedImageUrl, String feedAuthor, String feedDescription,
            List<RawEpisode> episodes) {
    }

    /** Parses an RSS/Atom body into the channel title + raw episodes. Package-visible for tests. */
    ParsedFeed parse(byte[] body, String url) throws FetchException {
        try (var in = new ByteArrayInputStream(body)) {
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(in));
            // Channel-level iTunes info: the show cover + author, stamped onto every item so a snapshot can
            // fall back to the feed cover / feed author when the item declares none (§4.2, DisplaySnapshot).
            String feedImageUrl = null;
            String feedAuthor = null;
            if (feed.getModule(FeedInformation.URI) instanceof FeedInformation channel) {
                feedImageUrl = channel.getImage() != null ? channel.getImage().toString() : null;
                feedAuthor = blankToNull(channel.getAuthor());
            }
            // Fall back to the standard RSS <image> when there's no itunes:image.
            if (feedImageUrl == null && feed.getImage() != null) {
                feedImageUrl = blankToNull(feed.getImage().getUrl());
            }
            // Both channel-level sources through the same door as the item-level ones.
            feedImageUrl = mediaUrl(feedImageUrl);
            String feedDescription = blankToNull(feed.getDescription());
            List<SyndEntry> entries = feed.getEntries();
            if (entries.size() > MAX_ITEMS) {
                throw new FetchException("Feed carries " + entries.size() + " items, past the " + MAX_ITEMS
                        + " item limit, for " + url);
            }
            List<RawEpisode> episodes = new ArrayList<>(entries.size());
            for (SyndEntry entry : entries) {
                episodes.add(toRawEpisode(entry, feedImageUrl, feedAuthor));
            }
            return new ParsedFeed(feed.getTitle(), feedImageUrl, feedAuthor, feedDescription, episodes);
        } catch (FetchException e) {
            // Already the right exception with the right message — the catch-all below would bury it under
            // "Failed to parse feed body", which is not what happened.
            throw e;
        } catch (Exception e) {
            throw new FetchException("Failed to parse feed body from " + url, e);
        }
    }

    private static RawEpisode toRawEpisode(SyndEntry entry, String feedImageUrl, String feedAuthor) {
        String guid = entry.getUri() != null ? entry.getUri() : entry.getLink();
        String title = entry.getTitle() != null ? entry.getTitle() : "";
        String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
        String audioUrl = mediaUrl(firstAudioEnclosure(entry));
        Instant publishedAt = notInTheFuture(entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant()
                : (entry.getUpdatedDate() != null ? entry.getUpdatedDate().toInstant() : null));

        Integer season = null;
        Integer episodeNumber = null;
        Duration duration = null;
        String imageUrl = null;
        String author = null;
        String subtitle = null;
        List<String> tags = new ArrayList<>();
        if (entry.getModule(EntryInformation.URI) instanceof EntryInformation itunes) {
            season = itunes.getSeason();
            episodeNumber = itunes.getEpisode();
            if (itunes.getDuration() != null) {
                duration = Duration.ofMillis(itunes.getDuration().getMilliseconds());
            }
            imageUrl = itunes.getImage() != null ? mediaUrl(itunes.getImage().toString()) : null;
            author = blankToNull(itunes.getAuthor());
            subtitle = blankToNull(itunes.getSubtitle());
            if (itunes.getKeywords() != null) {
                for (String keyword : itunes.getKeywords()) {
                    addTag(tags, keyword);
                }
            }
        }
        // Plain RSS <category> elements also contribute tags.
        for (SyndCategory category : entry.getCategories()) {
            addTag(tags, category.getName());
        }
        // Episode author falls back to the channel author; artwork falls back to the feed cover (in the snapshot).
        String resolvedAuthor = author != null ? author : feedAuthor;
        // v1 is RSS-only → everything is PUBLIC (ARCHITECTURE §10).
        return new RawEpisode(guid, title, description, audioUrl, publishedAt,
                season, episodeNumber, duration, imageUrl, feedImageUrl, resolvedAuthor, subtitle, tags,
                Access.PUBLIC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Adds a trimmed, non-blank, de-duplicated tag. */
    private static void addTag(List<String> tags, String raw) {
        if (raw == null) {
            return;
        }
        String tag = raw.trim();
        if (!tag.isEmpty() && !tags.contains(tag)) {
            tags.add(tag);
        }
    }

    /**
     * A media URL a feed supplied, or {@code null} when it is not one this host will hand to a browser.
     *
     * <p>Applied at ingestion, which is the only place it is cheap. These values are stored and later
     * emitted as an {@code <img src>}, as the player's source and as {@code og:image} / {@code og:audio},
     * and nothing in the feed path checked their scheme (core#196). Little of that is exploitable today,
     * and not by accident: {@code IndexHtmlService.escape()} escapes correctly for an attribute, and
     * neither {@code img} nor {@code audio} executes a {@code javascript:} URL. But the CSP is then the
     * only containment left, and it has to stay wide — {@code img-src 'self' data: https:} — precisely
     * because feed artwork comes from hosts nobody here chose. An allow-list at the door costs nothing.
     *
     * <p>{@code data:} is excluded deliberately, for a URL that is stored and re-served: an unbounded
     * inline payload in a column that every listing reads is a different problem from a link.
     */
    private static String mediaUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? trimmed : null;
    }

    /**
     * Refuses a 200 that is plainly not a feed.
     *
     * <p>An {@code Accept} header is sent and the answer's type was never looked at, so a host replying 200
     * with an HTML login page or a captcha — which is what a paywalled or rate-limiting host does — failed
     * inside Rome as "Failed to parse feed body", and the admin could not tell a broken feed from a wrong
     * URL or an expired subscription (core#184).
     *
     * <p>Deliberately a denylist of one, not an allowlist. Feeds are served as {@code application/rss+xml},
     * {@code application/atom+xml}, {@code application/xml}, {@code text/xml}, sometimes
     * {@code text/plain}, and sometimes with no type at all; refusing everything unfamiliar would break
     * working feeds to catch a mistake Rome will catch anyway. What {@code text/html} buys is the *message*:
     * the one wrong answer common enough to name.
     */
    private static void requireFeedLikeBody(HttpResponse<byte[]> response, String url) throws FetchException {
        String contentType = response.headers().firstValue("content-type").orElse("")
                .split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (contentType.equals("text/html") || contentType.equals("application/xhtml+xml")) {
            throw new FetchException("The feed URL answered with a web page (" + contentType
                    + ") rather than a feed — check the address, or whether the host needs a login: " + url);
        }
    }

    /**
     * A publication date, unless the feed claims one in the future.
     *
     * <p>Nothing in the feed path compared a {@code pubDate} against now, so one typo in a feed — or a host
     * with a wrong clock — pinned an episode at the top of {@code ?order=newest} <em>permanently</em> and
     * made it the last element of {@code findNavSequenceIds}, with no admin correction and no warning
     * (core#184). A *missing* date was already handled cleanly; a wrong one was not.
     *
     * <p>Treated as absent rather than clamped to now: a date this host invented is a worse answer than no
     * date, and the surrounding code already knows what to do without one. The tolerance is a day, because
     * a feed generator in a timezone ahead of UTC publishing "today" is ordinary and not an error.
     */
    private static Instant notInTheFuture(Instant published) {
        if (published == null) {
            return null;
        }
        if (published.isAfter(Instant.now().plus(Duration.ofDays(1)))) {
            log.warn("Ignoring a publication date in the future: {}", published);
            return null;
        }
        return published;
    }

    private static String firstAudioEnclosure(SyndEntry entry) {
        String firstAny = null;
        for (SyndEnclosure enclosure : entry.getEnclosures()) {
            if (enclosure.getUrl() == null) {
                continue;
            }
            if (firstAny == null) {
                firstAny = enclosure.getUrl();
            }
            String enclosureType = enclosure.getType();
            if (enclosureType != null && enclosureType.startsWith("audio")) {
                return enclosure.getUrl();
            }
        }
        return firstAny;
    }
}
