// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

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

    private static final SourceCapabilities CAPABILITIES =
            new SourceCapabilities(true, false, true, false);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "Mosaicast/1.0 (+https://github.com/mosaicast)";

    private final HttpClient http;

    public RssFeedSource() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
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
        ParsedFeed parsed = parse(response.body(), cfg.url());
        String etag = response.headers().firstValue("ETag").orElse(null);
        String lastModified = response.headers().firstValue("Last-Modified").orElse(null);
        return FetchResult.changed(parsed.episodes(), etag, lastModified, parsed.feedTitle());
    }

    private HttpResponse<byte[]> get(SourceConfig cfg) throws FetchException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.url()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .timeout(TIMEOUT)
                .GET();
        if (cfg.etag() != null) {
            req.header("If-None-Match", cfg.etag());
        }
        if (cfg.lastModified() != null) {
            req.header("If-Modified-Since", cfg.lastModified());
        }
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            throw new FetchException("Feed fetch I/O error for " + cfg.url(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Feed fetch interrupted for " + cfg.url(), e);
        }
    }

    /** Parsed feed: the channel title plus its items. */
    record ParsedFeed(String feedTitle, List<RawEpisode> episodes) {
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
            List<RawEpisode> episodes = new ArrayList<>(feed.getEntries().size());
            for (SyndEntry entry : feed.getEntries()) {
                episodes.add(toRawEpisode(entry, feedImageUrl, feedAuthor));
            }
            return new ParsedFeed(feed.getTitle(), episodes);
        } catch (Exception e) {
            throw new FetchException("Failed to parse feed body from " + url, e);
        }
    }

    private static RawEpisode toRawEpisode(SyndEntry entry, String feedImageUrl, String feedAuthor) {
        String guid = entry.getUri() != null ? entry.getUri() : entry.getLink();
        String title = entry.getTitle() != null ? entry.getTitle() : "";
        String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
        String audioUrl = firstAudioEnclosure(entry);
        Instant publishedAt = entry.getPublishedDate() != null
                ? entry.getPublishedDate().toInstant()
                : (entry.getUpdatedDate() != null ? entry.getUpdatedDate().toInstant() : null);

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
            imageUrl = itunes.getImage() != null ? itunes.getImage().toString() : null;
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
