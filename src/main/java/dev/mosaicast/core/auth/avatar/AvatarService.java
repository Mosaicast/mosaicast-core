// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.mosaicast.core.auth.LinkedIdentity;
import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serves every user's avatar as bytes (ARCHITECTURE §8.7).
 *
 * <p><strong>The host proxies; it never redirects.</strong> A {@code 302} to Discord's CDN would put the
 * snowflake — the {@code external_id} §8.2 keeps server-side — into every page that shows the picture, and
 * hand the CDN a hit from every visitor's browser. Everything else here follows from carrying the bytes
 * ourselves: a byte cap so one picture cannot exhaust memory, a content-type whitelist so the CDN cannot
 * hand back something that is not an image, no redirects followed, and no provider headers passed on.
 *
 * <p><strong>Cached in memory, never stored.</strong> A picture the host keeps a copy of is a picture the
 * host has to moderate, retain and erase — the whole reason §8.7 has no uploads. The cache is bounded by
 * total bytes rather than entry count, expires so a changed provider picture propagates, remembers failures
 * briefly so a broken avatar is not an outbound request per page view, and is evicted the moment a user
 * changes or unlinks their source.
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    /** What the proxy will pass on. Anything else from the CDN is a bug or an attack, never a picture. */
    private static final List<String> ALLOWED_TYPES =
            List.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final UserRepository users;
    private final LinkedIdentityRepository identities;
    private final Map<String, AvatarSource> sources;
    private final AvatarProperties properties;
    private final HttpClient http;
    private final Cache<UUID, Avatar> cache;

    public AvatarService(UserRepository users, LinkedIdentityRepository identities,
                         List<AvatarSource> sources, AvatarProperties properties) {
        this.users = users;
        this.identities = identities;
        this.sources = sources.stream()
                .collect(java.util.stream.Collectors.toMap(AvatarSource::provider, s -> s));
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                // A provider avatar URL has no business redirecting, and following one would take the fetch
                // to a host this code did not compose — the property the whole design rests on.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout())
                .build();
        this.cache = Caffeine.newBuilder()
                .maximumWeight(properties.cacheBytes())
                .weigher((UUID key, Avatar value) -> value.bytes().length)
                .expireAfter(new Expiry<UUID, Avatar>() {
                    @Override
                    public long expireAfterCreate(UUID key, Avatar value, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(
                                (value.fromProvider() ? properties.ttl() : properties.failureTtl())
                                        .toMillis());
                    }

                    @Override
                    public long expireAfterUpdate(UUID key, Avatar value, long currentTime,
                                                  long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(UUID key, Avatar value, long currentTime,
                                                long currentDuration) {
                        // Reading does not extend the life of a picture: the point of the TTL is that a
                        // changed provider avatar propagates, and a popular user's stale avatar would
                        // otherwise be refreshed last rather than first.
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * A user's avatar.
     *
     * @param bytes        the image
     * @param contentType  its media type
     * @param etag         a strong validator, so browsers revalidate instead of refetching
     * @param fromProvider whether these bytes came from a provider (as opposed to being generated), which
     *                     decides which TTL applies
     */
    public record Avatar(byte[] bytes, String contentType, String etag, boolean fromProvider) {
    }

    /**
     * The avatar for a user, from cache when possible.
     *
     * @param userId whose avatar
     * @return the avatar, or empty when there is no such user
     */
    public Optional<Avatar> avatarFor(UUID userId) {
        Avatar cached = cache.getIfPresent(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<User> found = users.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();
        Avatar avatar = fetchOrGenerate(user);
        cache.put(userId, avatar);
        return Optional.of(avatar);
    }

    /**
     * Drops a user's cached picture.
     *
     * <p>Called whenever the thing the cache is a copy of changes: the chosen source, an unlink, an
     * erasure. Without it a user who switches back to the generated avatar keeps showing the old one for a
     * quarter of an hour, which reads as the setting not working.
     */
    public void invalidate(UUID userId) {
        cache.invalidate(userId);
    }

    /**
     * The picture from the user's chosen provider, or the generated one.
     *
     * <p>Every failure lands on the generated avatar rather than an error: an avatar is decoration, and a
     * provider being slow or having deleted a picture is not a reason for a page to break.
     */
    private Avatar fetchOrGenerate(User user) {
        String provider = user.getAvatarProvider();
        if (provider != null) {
            Optional<byte[]> fetched = fetchFrom(user, provider);
            if (fetched.isPresent()) {
                return new Avatar(fetched.get(), "image/png", strongEtag(user), true);
            }
        }
        return new Avatar(
                GeneratedAvatar.svgFor(user.getId(), user.getDisplayName()),
                GeneratedAvatar.MIME,
                GeneratedAvatar.etagFor(user.getId(), user.getDisplayName()),
                false);
    }

    private Optional<byte[]> fetchFrom(User user, String provider) {
        AvatarSource source = sources.get(provider);
        Optional<LinkedIdentity> identity = identities.findByUserIdAndProvider(user.getId(), provider);
        if (source == null || identity.isEmpty()) {
            return Optional.empty();
        }
        Optional<URI> url = source.urlFor(
                identity.get().getExternalId(), identity.get().getAvatarRef(), properties.sizePx());
        if (url.isEmpty()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(url.get())
                    .timeout(properties.timeout())
                    .header("Accept", String.join(", ", ALLOWED_TYPES))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return read(response);
        } catch (IOException e) {
            log.debug("Avatar fetch for {} from {} failed: {}", user.getId(), provider, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Reads a capped body, having checked what the provider says it is.
     *
     * <p>The cap is enforced while reading rather than from {@code Content-Length}, which is a claim rather
     * than a fact: a header saying 1 KB followed by a gigabyte is the oldest version of this trick.
     */
    private Optional<byte[]> read(HttpResponse<InputStream> response) throws IOException {
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            String contentType = response.headers().firstValue("content-type").orElse("")
                    .split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!ALLOWED_TYPES.contains(contentType)) {
                return Optional.empty();
            }
            byte[] bytes = body.readNBytes(properties.maxImageBytes() + 1);
            if (bytes.length > properties.maxImageBytes()) {
                log.debug("Avatar refused: larger than {} bytes", properties.maxImageBytes());
                return Optional.empty();
            }
            return Optional.of(bytes);
        }
    }

    /**
     * A validator over what actually decides the bytes: the chosen source and its reference.
     *
     * <p>Keyed on those rather than on the image, so a cold start after a restart costs revalidations
     * rather than refetches — the price of holding the cache only in memory.
     */
    private String strongEtag(User user) {
        String ref = identities.findByUserIdAndProvider(user.getId(), user.getAvatarProvider())
                .map(LinkedIdentity::getAvatarRef)
                .orElse("none");
        return "\"" + user.getAvatarProvider() + "-" + ref + "\"";
    }

    /** How long a response may be considered fresh by a browser — the same TTL the server applies. */
    public Duration browserTtl() {
        return properties.ttl();
    }
}
