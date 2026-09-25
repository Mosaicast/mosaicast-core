// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

/**
 * A 400 whose reason has a stable name, so the shell can say it in the visitor's language (core#192).
 *
 * <p>The message stays English and stays in the problem's {@code detail}, where logs, API clients and older
 * shells read it. The {@code code} travels beside it as a problem property; the shell looks it up in its
 * catalog and falls back to the English text when it does not know the code. Without it, a German admin page
 * showed "Feed URL must be an http(s) URL" in the middle of German copy, and the only fix was for the shell
 * to match on English sentences.
 *
 * <p>Codes are part of the contract with the shell: add them freely, never rename one.
 */
public class CodedBadRequest extends IllegalArgumentException {

    private final String code;

    /**
     * @param code    a stable, dotted name for the reason, e.g. {@code feed.url.notHttp}
     * @param message the English explanation, for the problem's {@code detail}
     */
    public CodedBadRequest(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable name of the reason. */
    public String code() {
        return code;
    }
}
