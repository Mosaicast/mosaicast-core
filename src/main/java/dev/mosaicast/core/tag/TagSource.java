// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

/**
 * Who put a tag on an episode (ARCHITECTURE §6.1).
 *
 * <p>Provenance is what makes the vocabulary shareable. Without it the reconciler's per-poll overwrite was
 * the whole story — every tag on an episode belonged to the feed, so anything anyone else added was gone at
 * the next fetch. With it the wipe narrows to {@link #FEED}, and the three rules the SDK promises plugins
 * become enforceable rather than merely stated: a plugin's write is recorded as its own, it can remove only
 * its own, and an operator can see who put a tag there.
 */
public final class TagSource {

    /** The feed said so: {@code itunes:keywords} or {@code <category>}, rewritten on every poll. */
    public static final String FEED = "feed";

    /** A podcaster said so in admin — survives polls, which is the point of the column. */
    public static final String MANUAL = "manual";

    private static final String PLUGIN_PREFIX = "plugin:";

    private TagSource() {
    }

    /** The source recorded for a plugin's own writes. */
    public static String plugin(String pluginId) {
        return PLUGIN_PREFIX + pluginId;
    }

    /** Whether a source string names a plugin at all (as opposed to the feed or a podcaster). */
    public static boolean isPlugin(String source) {
        return source != null && source.startsWith(PLUGIN_PREFIX);
    }
}
