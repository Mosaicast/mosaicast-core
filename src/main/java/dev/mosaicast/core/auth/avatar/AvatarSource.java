// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import java.net.URI;
import java.util.Optional;

/**
 * Where one provider's avatar lives (ARCHITECTURE §8.7).
 *
 * <p><strong>The URL is composed here, in code, from a constant host.</strong> That is the security property
 * the whole design rests on: nothing attacker-influenced reaches the outbound fetch, so there is no SSRF to
 * filter rather than a filter to get right. A source that took a stored URL and returned it would give that
 * away, and no amount of allow-listing afterwards would get it back.
 *
 * <p>Implementations are Spring beans, one per provider, discovered by {@link AvatarSources}. Adding
 * Patreon or Google is a new bean and nothing else.
 */
public interface AvatarSource {

    /** The provider key this source serves, matching {@code LinkedIdentity.provider}. */
    String provider();

    /**
     * The URL to fetch, built from the identity's stable id and the provider's own picture reference.
     *
     * @param externalId the provider's user id — never leaves the server; see §8.7
     * @param avatarRef  the provider's picture reference, or {@code null} when it has none
     * @param sizePx     the pixel size to request, where the provider supports asking
     * @return the URL, or empty when this identity has no picture to fetch
     */
    Optional<URI> urlFor(String externalId, String avatarRef, int sizePx);
}
