// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Discord's avatar CDN (ARCHITECTURE §8.7).
 *
 * <p>The URL shape is {@code cdn.discordapp.com/avatars/<snowflake>/<hash>.png}, and the snowflake in it is
 * exactly the {@code external_id} §8.2 keeps server-side — which is why this URL is only ever fetched by
 * the host and never handed to a browser. A redirect here would undo the entire mechanism.
 */
@Component
public class DiscordAvatarSource implements AvatarSource {

    private static final String HOST = "https://cdn.discordapp.com";

    /**
     * What a Discord snowflake and avatar hash may contain.
     *
     * <p>Both values come from the provider rather than a user, so this is not the security boundary — the
     * constant host is. It is here because a value that fails it cannot produce a working URL anyway, and
     * building one would turn a provider quirk into an outbound request to a path we did not intend.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    @Override
    public String provider() {
        return "discord";
    }

    @Override
    public Optional<URI> urlFor(String externalId, String avatarRef, int sizePx) {
        if (externalId == null || avatarRef == null
                || !SAFE.matcher(externalId).matches() || !SAFE.matcher(avatarRef).matches()) {
            return Optional.empty();
        }
        // Asking for a size is what keeps the byte cap from doing the work: Discord serves 1024px by
        // default, and the shell never renders an avatar larger than a few dozen pixels.
        return Optional.of(URI.create(
                HOST + "/avatars/" + externalId + "/" + avatarRef + ".png?size=" + sizePx));
    }
}
