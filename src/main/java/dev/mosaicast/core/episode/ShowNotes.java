// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import org.jsoup.Jsoup;

/**
 * Show notes as plain text — {@link DisplaySnapshot#descriptionText()} (SDK 0.16.0).
 *
 * <p>{@code description} is the feed's HTML verbatim and untrusted; a plugin drawing a card or writing an
 * {@code OgMeta} description needs the prose without the markup, and every plugin re-deriving it would mean
 * every plugin getting entities or a stray {@code >} wrong the way {@link EpisodeSummary}'s excerpt once did
 * (core#196). So the host derives it once, the same way that excerpt does now: parsed, not regexed.
 */
public final class ShowNotes {

    private ShowNotes() {
    }

    /**
     * The text of {@code html}: tags removed, entities decoded, whitespace collapsed to single spaces.
     *
     * @param html show-notes HTML; may be {@code null}
     * @return the plain text, never {@code null}; empty for absent or text-free input
     */
    public static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
    }

    /**
     * {@code snapshot} with {@code descriptionText} filled in when it is missing.
     *
     * <p>Snapshots stored before 0.16.0 have no plain text — the column is JSON, and the record reads an
     * absent field as {@code ""}. Filling it on the way out rather than rewriting every row means nothing
     * has to run before a plugin sees the right value, and the next feed poll compares equal rather than
     * rewriting every episode it has.
     *
     * @param snapshot a snapshot, possibly from an old row; may be {@code null}
     * @return a snapshot whose {@code descriptionText} matches its {@code description}, or {@code null}
     */
    public static DisplaySnapshot complete(DisplaySnapshot snapshot) {
        if (snapshot == null || !snapshot.descriptionText().isEmpty()
                || snapshot.description() == null || snapshot.description().isBlank()) {
            return snapshot;
        }
        return new DisplaySnapshot(snapshot.title(), snapshot.description(), snapshot.audioUrl(),
                snapshot.publishedAt(), snapshot.duration(), snapshot.imageUrl(), snapshot.feedImageUrl(),
                snapshot.author(), snapshot.subtitle(), plainText(snapshot.description()));
    }
}
