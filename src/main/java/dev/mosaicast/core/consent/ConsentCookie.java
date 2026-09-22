// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The visitor's granted categories, mirrored into a cookie so the <em>server</em> can act on them.
 *
 * <p>The decision itself lives in {@code localStorage} (`mc.consent`), which the server never sees. That is
 * fine for {@code ctx.consent.has()}, which is advisory anyway — but it means the Content-Security-Policy,
 * the one thing a plugin cannot talk its way past, had to be written blind and therefore allowed every
 * declared origin regardless of what the visitor chose. This cookie is what closes that: it is the same
 * decision, in the one place an HTTP response can read it.
 *
 * <p><strong>What this is not.</strong> It is not a trust boundary against the visitor — they can edit their
 * own cookie, and widening their own policy harms only them. It is not absolute containment against a
 * determined plugin either: plugin code shares the page's origin, so it could write a forged value and wait
 * for the next navigation. What it does do is make a refusal take effect at the network layer for the whole
 * of the current page — the case where a plugin ignores {@code has()}, by malice or by bug, is now a blocked
 * request rather than a silent one — and turn evasion into a deliberate, forged, detectable act.
 *
 * <p>Strictly necessary: it exists solely to carry out the visitor's own refusal, so it needs no consent of
 * its own (§25 (2) TDDDG). It is disclosed in {@link CoreStorageInventory} like everything else.
 */
public final class ConsentCookie {

    /** Cookie name; mirrored in the shell's `ConsentContext` and disclosed in the storage inventory. */
    public static final String NAME = "mc_consent";

    private ConsentCookie() {
    }

    /**
     * The categories this request claims to have granted.
     *
     * <p>Absent cookie means <strong>nothing granted</strong> — the same default-deny the client applies
     * before a decision exists, so a first visit gets the narrow policy rather than the wide one.
     *
     * @param request the current request
     * @return granted category names, lowercased; empty when there is no cookie or it is empty
     */
    public static Set<String> grantedIn(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Set.of();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .map(ConsentCookie::parse)
                .orElseGet(Set::of);
    }

    /**
     * Parses the value: category names separated by {@code .} (a dot needs no encoding in a cookie value,
     * unlike the comma and space that {@code Set-Cookie} treats as separators).
     *
     * <p>The value is visitor-controlled and ends up compared against manifest-declared category names, so
     * anything that is not a plain category token is dropped rather than trusted.
     */
    private static Set<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String part : value.split("\\.")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && trimmed.matches("[a-z0-9_-]{1,40}")) {
                categories.add(trimmed);
            }
        }
        return categories;
    }
}
