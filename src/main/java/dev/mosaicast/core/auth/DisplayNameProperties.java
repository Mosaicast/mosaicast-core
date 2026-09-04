// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a display name may be (ARCHITECTURE §8.6).
 *
 * <p><strong>The word list lives here and not in code</strong> because an operator's language and
 * jurisdiction are not ours to guess: a German-market install needs German slurs, a self-hoster needs
 * their own list entirely, and neither should have to fork the host to get one. §8.6 is equally explicit
 * that this is a speed bump — word lists lose to compounds and produce false positives — and that the
 * control is the revert in §8.6.1.
 *
 * @param minLength      shortest permitted name, in codepoints
 * @param maxLength      longest permitted name, in codepoints
 * @param renameCooldown how long a user must wait between their own renames
 * @param reserved       names nobody may take, matched on the uniqueness key
 * @param blocked        substrings refused anywhere in a name, matched on the aggressive key
 */
@ConfigurationProperties(prefix = "mosaicast.display-name")
public record DisplayNameProperties(
        Integer minLength,
        Integer maxLength,
        Duration renameCooldown,
        List<String> reserved,
        List<String> blocked) {

    private static final int DEFAULT_MIN = 3;
    private static final int DEFAULT_MAX = 32;
    private static final Duration DEFAULT_COOLDOWN = Duration.ofHours(24);

    public DisplayNameProperties {
        minLength = minLength == null ? DEFAULT_MIN : minLength;
        maxLength = maxLength == null ? DEFAULT_MAX : maxLength;
        renameCooldown = renameCooldown == null ? DEFAULT_COOLDOWN : renameCooldown;
        reserved = reserved == null ? List.of() : List.copyOf(reserved);
        blocked = blocked == null ? List.of() : List.copyOf(blocked);
    }

    /**
     * The reserved names as uniqueness keys, so a comparison here uses the same folding a name does — a
     * reserved list matched on raw strings is one Cyrillic {@code а} away from useless.
     */
    public Set<String> reservedKeys() {
        return reserved.stream()
                .map(DisplayNames::canonicalise)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The blocked substrings as match keys, for the same reason {@link #reservedKeys()} folds. */
    public Set<String> blockedKeys() {
        return blocked.stream()
                .map(DisplayNames::matchKey)
                .filter(key -> !key.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
