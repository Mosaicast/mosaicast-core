// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import java.util.Locale;

/**
 * The fixed message types core sends (ARCHITECTURE §17).
 *
 * <p><strong>Kinds, not text.</strong> A {@code system} notification names one of these and the shell
 * translates it, so the wording lives in the message catalogs like every other user-visible string
 * (§12.7) and no caller can put a sentence of their own into the site's own voice. That restraint is the
 * point: §8.6.1 stops an admin choosing a reverted name, and it would be worth nothing if the same admin
 * could type the explanation that arrives with it.
 *
 * <p>Core-only, and deliberately not extensible by plugins. A plugin sends its own text (§17.1); what it
 * must not be able to do is speak as the host.
 */
public enum NotificationKind {

    /**
     * An admin walked this user's display name back (§8.6.1).
     *
     * <p>The reason this whole section exists: a name that changes with no explanation reads as a bug or
     * a break-in. Parameters carry the previous and current names, so the notice can say what happened
     * without anybody having authored a sentence about this particular person.
     */
    NAME_REVERTED,

    /**
     * The data export this user asked for is ready (§12.8.1). Carries {@code expiresAt}, because the archive is
     * deleted then and a notice that does not say so invites a download link that no longer works.
     */
    EXPORT_READY,

    /**
     * Feedback arrived for a reader (§20.3). At most one is unread per reader: a report arriving while one is
     * unread raises its {@code count} instead of adding a row, so a burst of spam is one bell, not a hundred.
     */
    FEEDBACK_NEW,

    /**
     * A reader answered this user's feedback. An {@code admin}-source kind, unlike the rest: the text is the
     * reader's, attributable and logged as a warning is, but it is not a warning and is not listed as one.
     */
    FEEDBACK_REPLY;

    /** The stored form — lower-cased with dashes, matching the problem types and role names on the wire. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
