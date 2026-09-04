// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

/**
 * A display name the host will not accept (ARCHITECTURE §8.6).
 *
 * <p>The {@link Reason} carries a stable problem type each, following the external-service vocabulary in
 * §16: a caller that cannot tell the four apart cannot act on any of them, and the UI has to translate the
 * refusal rather than print an English sentence (§13). {@code REFUSED} deliberately merges "reserved" and
 * "on the blocked list" — the user's next step is the same either way, and separating them would let
 * someone read the word list back out one attempt at a time.
 */
public class DisplayNameRejectedException extends RuntimeException {

    /** Why the name was refused; each maps to its own problem type and status. */
    public enum Reason {
        /** Too short, too long, or nothing but invisible characters. */
        INVALID,
        /** Reserved by the host, or matching the operator's blocked list. */
        REFUSED,
        /** Somebody else already holds this name. */
        TAKEN,
        /** The user renamed too recently, or an admin reverted them and the freeze has not expired. */
        LOCKED
    }

    private final transient Reason reason;

    public DisplayNameRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
