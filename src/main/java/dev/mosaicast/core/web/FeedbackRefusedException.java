// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * A feedback report the host refused (ARCHITECTURE §20.4), each case with its own problem type so the form can
 * tell a person what to do. It deliberately tells a bot no more than that: every proof-of-work failure is the
 * same {@code feedback-challenge-failed}, whichever check tripped.
 */
public final class FeedbackRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final Duration retryAfter;

    private FeedbackRefusedException(HttpStatus status, String slug, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.slug = slug;
        this.retryAfter = retryAfter;
    }

    /** 400: too long, too many links, an unknown kind, an empty message. */
    public static FeedbackRefusedException invalid(String message) {
        return new FeedbackRefusedException(HttpStatus.BAD_REQUEST, "feedback-invalid", message, null);
    }

    /** 400: the proof of work is missing, expired, spent, wrong or came back too fast. */
    public static FeedbackRefusedException challengeFailed() {
        return new FeedbackRefusedException(HttpStatus.BAD_REQUEST, "feedback-challenge-failed",
                "The check before sending did not pass. Try again.", null);
    }

    /** 429: this sender has sent enough for now. */
    public static FeedbackRefusedException rateLimited(Duration retryAfter) {
        return new FeedbackRefusedException(HttpStatus.TOO_MANY_REQUESTS, "feedback-rate-limited",
                "Too many reports for now. Try again later.", retryAfter);
    }

    /** 503: the anonymous queue is full until readers catch up; signed-in senders are never refused this. */
    public static FeedbackRefusedException busy(Duration retryAfter) {
        return new FeedbackRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "feedback-busy",
                "Too many reports are waiting. Sign in, or try again later.", retryAfter);
    }

    /** 403: a reader blocked this user from sending. */
    public static FeedbackRefusedException blocked() {
        return new FeedbackRefusedException(HttpStatus.FORBIDDEN, "feedback-blocked",
                "You can't send feedback on this site.", null);
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
