// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import java.util.Locale;

/**
 * The host's canonical tag key (ARCHITECTURE §6.1, SDK {@code Tags}).
 *
 * <p>Tags began as one feed's verbatim {@code itunes:keywords}, where {@code Maritime}, {@code maritime}
 * and {@code maritime } being three rows was untidy but harmless. As a vocabulary several writers share it
 * is not: the feed, a podcaster and every plugin would each fragment it slightly differently, and two
 * things labelled {@code lore} would still have no relationship to each other.
 *
 * <p>So the host owns the key and everyone else sends any spelling: trim, collapse internal whitespace,
 * casefold. The spelling a writer used survives as the vocabulary's display {@link Tag#getLabel() label},
 * kept from first use — normalising is about making writers converge, not about lower-casing what a
 * visitor reads.
 *
 * <p><strong>This is the only copy of the rule.</strong> {@code V28__tag_vocabulary.sql} inlines it once to
 * canonicalise the rows that existed before it; from there on the database stores what this class produced.
 * A SQL function would have been a second source of truth free to disagree — including about what
 * {@code lower()} means, which is locale-dependent in Postgres and pinned to {@link Locale#ROOT} here.
 */
public final class TagKeys {

    /** The longest key the vocabulary accepts — a tag, not a sentence someone pasted into a keywords field. */
    public static final int MAX_LENGTH = 100;

    private TagKeys() {
    }

    /**
     * The canonical key for any spelling of a tag.
     *
     * @param raw any spelling, possibly {@code null}
     * @return the canonical key, or {@code ""} when the input carries nothing — callers treat blank as
     *         "not a tag" rather than storing an entry nobody could name
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH).strip() : collapsed;
    }

    /**
     * The display label to keep for a tag written as {@code raw}: the author's spelling, tidied the same way
     * but with its casing intact.
     *
     * @param raw any spelling, possibly {@code null}
     * @return the label, or {@code ""} when the input carries nothing
     */
    public static String label(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.strip().replaceAll("\\s+", " ");
        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH).strip() : collapsed;
    }

    /** Whether a spelling can become a vocabulary entry at all. */
    public static boolean isUsable(String raw) {
        return !canonical(raw).isEmpty();
    }
}
