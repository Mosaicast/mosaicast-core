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
 * The operator's ceilings on plugin file storage (ARCHITECTURE §11, {@code mosaicast.plugin-blobs.*}).
 *
 * <p><strong>The operator's numbers always win.</strong> A manifest declares what a plugin wants; these
 * decide what an install actually grants, and the effective value is the smaller of the two. A plugin is not
 * rejected for asking too much — a plugin's portability should not depend on the most restrictive install it
 * might ever meet — it is simply granted less, and {@code quota()} tells it so.
 *
 * <p>Plugin blobs are the first <em>unbounded</em> write a plugin can make: doc-store documents and schema
 * rows are bounded by request size and by what a plugin's own backend writes, while a file is as large as
 * whoever uploads it. So the defaults are conservative and an operator raises them deliberately.
 *
 * @param maxFileBytes  the largest single file any plugin may store; default 10 MB
 * @param maxQuotaBytes the most any one plugin may occupy in total; default 256 MB
 * @param allowedMimeTypes the content types an install permits at all; a plugin's declared list is
 *                         intersected with this, so nothing outside it is reachable by declaring it
 */
@ConfigurationProperties(prefix = "mosaicast.plugin-blobs")
public record PluginBlobProperties(
        @DefaultValue("10485760") long maxFileBytes,
        @DefaultValue("268435456") long maxQuotaBytes,
        @DefaultValue({"image/png", "image/jpeg", "image/webp", "image/gif", "image/avif",
                "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav"})
        List<String> allowedMimeTypes) {

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
