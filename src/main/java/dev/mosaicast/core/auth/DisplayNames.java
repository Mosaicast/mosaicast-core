// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Canonicalisation of display names (ARCHITECTURE §8.6). The host owns the key: {@code display_name} keeps
 * the spelling that was typed and {@link #canonicalise(String)} produces the {@code display_key} uniqueness
 * is enforced on — the same split the tag vocabulary uses (§6.1.1), for the same reason. {@code Maritime}
 * and {@code maritime } converge without lower-casing what a visitor reads.
 *
 * <p><strong>This class is also called by the migration that backfills the column</strong>
 * ({@code V33__display_name}). That is deliberate and it is the reason canonicalisation lives in one pure,
 * dependency-free place: a backfill that computed keys differently from the runtime would leave a unique
 * index that rejects names the validator accepts, intermittently and only on unicode. If the algorithm ever
 * changes, existing keys are stale by definition and a new migration has to re-canonicalise them — there is
 * no version of this where the two can be allowed to drift.
 *
 * <p>Two keys, not one. {@link #canonicalise} folds only what one script borrows from another, because it
 * decides <em>uniqueness</em> and a user named {@code N30} must not collide with {@code Neo}.
 * {@link #matchKey} folds far more aggressively — digits, symbols, repetition — because it decides whether
 * a name matches the blocked-word list, where a false positive costs someone a second attempt and a false
 * negative costs the site a slur in its leaderboard.
 */
public final class DisplayNames {

    /**
     * Characters one script borrows from another, folded towards Latin.
     *
     * <p>Not a Unicode confusable table: a real one needs ICU4J, and §8.6 is explicit that folding raises
     * the cost of a lookalike rather than closing the class — the answer to impersonation is the revert in
     * §8.6.1, not a better table here. This covers the Cyrillic and Greek letters that are visually
     * identical to Latin in the fonts the shell actually ships, which is where the cheap attack lives.
     */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'),
            Map.entry('р', 'p'), Map.entry('с', 'c'), Map.entry('у', 'y'),
            Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ѕ', 's'),
            Map.entry('ј', 'j'), Map.entry('ԁ', 'd'), Map.entry('һ', 'h'),
            Map.entry('ԛ', 'q'), Map.entry('ԝ', 'w'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('т', 't'), Map.entry('в', 'b'),
            // Greek
            Map.entry('α', 'a'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'),
            Map.entry('ε', 'e'), Map.entry('ι', 'i'), Map.entry('κ', 'k'),
            Map.entry('μ', 'm'), Map.entry('ν', 'v'), Map.entry('τ', 't'),
            Map.entry('χ', 'x'), Map.entry('υ', 'u'));

    /** Substitutions that only the blocked-word list cares about (see {@link #matchKey}). */
    private static final Map<Character, Character> LEET = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('8', 'b'), Map.entry('@', 'a'),
            Map.entry('$', 's'), Map.entry('!', 'i'), Map.entry('|', 'i'), Map.entry('+', 't'));

    private DisplayNames() {
    }

    /**
     * The visible form: NFKC-normalised, stripped of characters that render as nothing, with runs of
     * whitespace collapsed to a single space and the ends trimmed.
     *
     * <p>Stripping the invisibles is not cosmetic. A zero-width joiner between two letters is a name that
     * looks identical to another one, passes any comparison, and cannot be typed by the person reporting it.
     *
     * @param raw what the user typed
     * @return the form to store in {@code display_name}, possibly empty
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            // Whitespace is tested before the category check below, because a tab is a *control* character:
            // classifying it first would delete it and turn "Ned<tab>Flanders" into one word rather than two.
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                out.append(' ');
                continue;
            }
            int type = Character.getType(cp);
            // Format and control characters render as nothing; so do the unassigned and private-use ranges,
            // which additionally render differently per platform. None of them belong in a name.
            if (type == Character.FORMAT || type == Character.CONTROL || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    /**
     * The uniqueness key for a display name: {@link #clean} plus casefolding and script-confusable folding.
     *
     * <p>Folds only cross-script lookalikes, never digits or symbols — this decides whether two people may
     * both exist, and refusing {@code N30} because {@code Neo} is taken would be a bug wearing the costume
     * of a security control.
     *
     * @param raw what the user typed
     * @return the form to store in {@code display_key}, empty when the name has no usable content
     */
    public static String canonicalise(String raw) {
        String cleaned = clean(raw).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            out.append(CONFUSABLES.getOrDefault(c, c));
        }
        return out.toString();
    }

    /**
     * The aggressive key the blocked-word list matches against: {@link #canonicalise} with leetspeak folded
     * and everything that is not a letter or digit removed, so spacing and punctuation cannot smuggle a word
     * past the list.
     *
     * <p>It is built <em>on</em> the uniqueness key rather than beside it, so the normalisation that decides
     * who may exist is the same one that decides what may be said (§8.6) — this only folds further. The
     * extra folding is one-directional on purpose: it may reject a name the uniqueness key would have
     * allowed, and never the other way round.
     *
     * @param raw what the user typed
     * @return a letters-and-digits-only key for substring matching
     */
    public static String matchKey(String raw) {
        String canonical = canonicalise(raw);
        StringBuilder out = new StringBuilder(canonical.length());
        for (int i = 0; i < canonical.length(); i++) {
            char c = LEET.getOrDefault(canonical.charAt(i), canonical.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The neutral name the host falls back to (§8.6.1): the floor a revert walks down to, and what a new
     * account gets when the provider's name is unusable.
     *
     * <p>Derived from the user id so it is stable, collision-free without a lookup, and says nothing about
     * the person — which is the entire requirement.
     *
     * @param userId the user's id
     * @return a name of the form {@code Listener 4f2a}
     */
    public static String generatedFor(UUID userId) {
        return "Listener " + userId.toString().substring(0, 4);
    }

    /** The number of codepoints in a cleaned name — length limits count characters, not UTF-16 units. */
    public static int length(String cleaned) {
        return cleaned.codePointCount(0, cleaned.length());
    }
}
