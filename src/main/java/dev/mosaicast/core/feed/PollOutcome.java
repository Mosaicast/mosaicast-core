// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

/**
 * The outcome of a single {@link FeedPipeline#poll(Feed)} — used by the scheduler and the "refresh now"
 * endpoint (ARCHITECTURE §5.4).
 *
 * @param status  what happened
 * @param result  the reconciliation summary when {@code status == RECONCILED}, otherwise {@code null}
 * @param message a human-readable detail for a failure, otherwise {@code null}
 */
public record PollOutcome(Status status, ReconcileResult result, String message) {

    /** Coarse outcome of a poll. */
    public enum Status {
        /** The feed changed and was reconciled. */
        RECONCILED,
        /** The feed was unchanged (HTTP 304); nothing to do. */
        NOT_MODIFIED,
        /** The feed has no pollable source (e.g. the manual feed). */
        SKIPPED,
        /** The fetch failed; the feed backed off and kept its last good state. */
        FAILED
    }

    public static PollOutcome reconciled(ReconcileResult result) {
        return new PollOutcome(Status.RECONCILED, result, null);
    }

    public static PollOutcome notModified() {
        return new PollOutcome(Status.NOT_MODIFIED, null, null);
    }

    public static PollOutcome skipped() {
        return new PollOutcome(Status.SKIPPED, null, null);
    }

    public static PollOutcome failed(String message) {
        return new PollOutcome(Status.FAILED, null, message);
    }
}
