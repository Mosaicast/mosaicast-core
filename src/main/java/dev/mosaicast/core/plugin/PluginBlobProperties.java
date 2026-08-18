// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The operator's side of plugin file storage (ARCHITECTURE §11.1, {@code mosaicast.plugin-blobs.*}).
 *
 * <p>Two kinds of number, and the distinction is the whole design:
 *
 * <ul>
 *   <li><strong>Defaults</strong> ({@code default-quota-bytes}, {@code default-max-file-bytes}) — what a
 *       plugin gets when nobody has said otherwise. Deliberately conservative, because plugin blobs are the
 *       first <em>unbounded</em> write a plugin can make: a document is bounded by request size, a file is
 *       as large as whoever uploads it.</li>
 *   <li><strong>Hard ceilings</strong> ({@code hard-quota-bytes}, {@code hard-max-file-bytes}) — the most an
 *       <em>admin</em> may grant through the UI. <strong>Unset by default</strong>, meaning the admin
 *       decides. They exist because ADMIN is a role inside the application (§8.5) while these properties are
 *       infrastructure: on an install where those are not the same person, an operator needs a bound the web
 *       UI cannot cross. On a single-podcaster install, leaving them unset is the right answer.</li>
 * </ul>
 *
 * <p>This replaces the earlier shape, where one property was both the default and the ceiling. That
 * conflation is what made "the wiki has outgrown its space" a redeploy rather than a decision: raising the
 * number raised it for every plugin on the install, and only from the environment.
 *
 * @param defaultQuotaBytes   what a plugin may store in total with no manifest ask and no admin grant
 * @param defaultMaxFileBytes the same for a single file
 * @param hardQuotaBytes      the most an admin may grant in total, or null for no bound
 * @param hardMaxFileBytes    the most an admin may grant for one file, or null for no bound
 * @param allowedMimeTypes    the content types this install permits at all; a plugin's declared list is
 *                            intersected with this, and <strong>no grant widens it</strong> — what a file
 *                            may be is a security question, not a capacity one
 */
@ConfigurationProperties(prefix = "mosaicast.plugin-blobs")
public record PluginBlobProperties(
        @DefaultValue("268435456") long defaultQuotaBytes,
        @DefaultValue("10485760") long defaultMaxFileBytes,
        Long hardQuotaBytes,
        Long hardMaxFileBytes,
        @DefaultValue({"image/png", "image/jpeg", "image/webp", "image/gif", "image/avif",
                "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav"})
        List<String> allowedMimeTypes) {

    /**
     * Clamps an admin's requested total to the operator's ceiling, if there is one.
     *
     * @param requested what the admin asked to grant
     * @return the grantable value
     */
    public long clampQuota(long requested) {
        return hardQuotaBytes == null ? requested : Math.min(requested, hardQuotaBytes);
    }

    /**
     * Clamps an admin's requested per-file limit to the operator's ceiling, if there is one.
     *
     * @param requested what the admin asked to grant
     * @return the grantable value
     */
    public long clampMaxFile(long requested) {
        return hardMaxFileBytes == null ? requested : Math.min(requested, hardMaxFileBytes);
    }

    /**
     * The install's allow-list, normalised.
     *
     * <p>{@code image/svg+xml} is dropped here rather than merely left out of the default: an operator
     * cannot re-enable SVG uploads through configuration, because the reason it is refused (§12.2 — a script
     * container wearing an image's extension) is not a matter of local taste, and nothing downstream
     * sanitises it.
     *
     * @return the permitted content types, lower-cased
     */
    public Set<String> allowed() {
        return allowedMimeTypes.stream()
                .filter(java.util.Objects::nonNull)
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .filter(type -> !type.isEmpty() && !type.equals("image/svg+xml"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
