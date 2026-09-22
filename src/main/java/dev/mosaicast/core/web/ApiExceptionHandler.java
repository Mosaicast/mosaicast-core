// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders errors as RFC 7807 {@code application/problem+json} (ARCHITECTURE §13).
 *
 * <p>Spring already maps its own web exceptions to {@link ProblemDetail}; this advice adds a
 * last-resort mapping for otherwise-unhandled exceptions so no raw stack trace ever reaches a client.
 * Error payloads stay English — the UI translates (ARCHITECTURE §12.7). Feature-specific problem types
 * (stable {@code type} codes) are added by their controllers as milestones land.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        problem.setType(URI.create("https://mosaicast.dev/problems/not-found"));
        return problem;
    }

    /**
     * A controller that needs a caller and has none — 401, matching what the filter chain's entry point
     * returns for the same condition.
     *
     * <p>Without this the catch-all below turns it into a 500: {@code ExceptionTranslationFilter} converts
     * an {@code AuthenticationException} into a 401, but only if it escapes the dispatcher, and
     * {@code @ControllerAdvice} runs first. So "you are not signed in" was reported as "the server broke",
     * which is both wrong and unhelpfully alarming.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ProblemDetail handleUnauthenticated(
            org.springframework.security.core.AuthenticationException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://mosaicast.dev/problems/unauthorized"));
        return problem;
    }

    /**
     * The doc-store refusal that is <em>not</em> about the caller's role — a key the plugin's manifest
     * reserves for its own backend. Its own {@code type} so a plugin author (and their tests) can tell the
     * two 403s apart without matching on English; see {@link BackendOwnedKeyException}.
     */
    @ExceptionHandler(BackendOwnedKeyException.class)
    public ProblemDetail handleBackendOwnedKey(BackendOwnedKeyException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://mosaicast.dev/problems/backend-owned-key"));
        return problem;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://mosaicast.dev/problems/forbidden"));
        return problem;
    }

    /**
     * A plugin upload refused for size (§11). 413 with its own {@code type}, worded apart from the type
     * refusal below: the two have different fixes — send a smaller file, versus delete something first — and
     * a client that cannot tell them apart cannot say which.
     */
    @ExceptionHandler(dev.mosaicast.core.plugin.BlobQuotaExceededException.class)
    public ProblemDetail handleBlobQuota(dev.mosaicast.core.plugin.BlobQuotaExceededException ex,
                                         WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
        problem.setTitle("Payload Too Large");
        problem.setType(URI.create("https://mosaicast.dev/problems/blob-quota-exceeded"));
        return problem;
    }

    /** A plugin upload refused for its content type, declared or actual (§11/§12.2). */
    @ExceptionHandler(dev.mosaicast.core.plugin.BlobTypeNotAllowedException.class)
    public ProblemDetail handleBlobType(dev.mosaicast.core.plugin.BlobTypeNotAllowedException ex,
                                        WebRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Unsupported Media Type");
        problem.setType(URI.create("https://mosaicast.dev/problems/blob-type-not-allowed"));
        return problem;
    }

    /**
     * A display name the host will not take (ARCHITECTURE §8.6).
     *
     * <p>A type and a status per reason, following the external-service vocabulary in §16: "too long",
     * "not available", "already taken" and "not yet" ask four different things of the person reading them,
     * and a UI that has to tell them apart by matching English cannot be translated (§13, §12.7).
     */
    @ExceptionHandler(dev.mosaicast.core.auth.DisplayNameRejectedException.class)
    public ProblemDetail handleDisplayNameRejected(
            dev.mosaicast.core.auth.DisplayNameRejectedException ex, WebRequest request) {
        HttpStatus status = ex.getReason() == dev.mosaicast.core.auth.DisplayNameRejectedException.Reason.INVALID
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.CONFLICT;
        String slug = ex.getReason().name().toLowerCase(java.util.Locale.ROOT);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Display name rejected");
        problem.setType(URI.create("https://mosaicast.dev/problems/display-name-" + slug));
        return problem;
    }

    /**
     * A notification the host will not send (ARCHITECTURE §17.1).
     *
     * <p>A status and a stable type per reason, matching the SDK's own vocabulary: a send refused by the
     * operator's cap is a routine outcome a scheduled sender must back off from, and one refused for a bad
     * link is a bug that will fail the same way next time. A caller that cannot tell them apart can act on
     * neither.
     */
    @ExceptionHandler(dev.mosaicast.plugin.api.NotificationException.class)
    public ProblemDetail handleNotificationRefused(
            dev.mosaicast.plugin.api.NotificationException ex, WebRequest request) {
        HttpStatus status = ex.retryable() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
        String slug = ex.reason().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Notification refused");
        problem.setType(URI.create("https://mosaicast.dev/problems/notification-" + slug));
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://mosaicast.dev/problems/conflict"));
        return problem;
    }

    @ExceptionHandler(ExplicitLinkRequiredException.class)
    public ProblemDetail handleExplicitLinkRequired(ExplicitLinkRequiredException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Explicit Linking Required");
        problem.setType(URI.create("https://mosaicast.dev/problems/explicit-link-required"));
        return problem;
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail handleTooManyRequests(TooManyRequestsException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Too Many Requests");
        problem.setType(URI.create("https://mosaicast.dev/problems/too-many-requests"));
        return problem;
    }

    /**
     * Every external-service refusal (ARCHITECTURE §12.7), each keeping its own status and {@code type}.
     *
     * <p>One handler rather than six, because the mapping is entirely mechanical — the exception knows its
     * slug and the switch knows its status. What is <em>not</em> collapsed is the set of statuses: a caller
     * that cannot tell 409 "nobody configured this" from 503 "we are full" from 429 "you asked too often"
     * cannot act on any of them, and each has a different fix.
     *
     * <p>{@code Retry-After} rides along where there is a real answer to "when, then".
     */
    @ExceptionHandler(dev.mosaicast.core.external.error.ExternalServiceException.class)
    public ResponseEntity<ProblemDetail> handleExternalService(
            dev.mosaicast.core.external.error.ExternalServiceException ex, WebRequest request) {
        HttpStatus status = switch (ex) {
            case dev.mosaicast.core.external.error.NoProviderConfiguredException ignored -> HttpStatus.CONFLICT;
            case dev.mosaicast.core.external.error.ProviderMisconfiguredException ignored -> HttpStatus.CONFLICT;
            case dev.mosaicast.core.external.error.ExternalBusyException ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            case dev.mosaicast.core.external.error.ExternalRateLimitedException ignored ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case dev.mosaicast.core.external.error.ExternalTimeoutException ignored -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://mosaicast.dev/problems/" + ex.problemType()));

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex instanceof dev.mosaicast.core.external.error.ExternalRateLimitedException limited
                && limited.retryAfter() != null) {
            response.header("Retry-After", String.valueOf(Math.max(1, limited.retryAfter().toSeconds())));
        } else if (ex instanceof dev.mosaicast.core.external.error.ExternalBusyException) {
            response.header("Retry-After", "5");
        }
        return response.body(problem);
    }

    /**
     * A client error, with the project's own validation message when there is one.
     *
     * <p>The message used to be returned verbatim, which is right for the messages this project writes —
     * they are addressed to the caller — and wrong for the ones it does not. The same handler catches
     * IAEs thrown deep inside the framework and its libraries, whose messages carry internal type names,
     * field paths and occasionally file paths, and those went straight into the response body (core#196).
     * Every other handler here is careful about this; the catch-all below returns a generic 500 and logs
     * separately.
     *
     * <p>So the message is passed on only when this project raised it. "Ours" is decided by where the
     * throw came from, not by inspecting the text: a message that happens to mention a package name is a
     * bad heuristic, and a stack trace is a fact.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex, WebRequest request) {
        String detail = raisedByThisProject(ex) ? ex.getMessage() : "The request could not be accepted.";
        if (!raisedByThisProject(ex)) {
            log.warn("A library raised IllegalArgumentException; the detail was not passed on", ex);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://mosaicast.dev/problems/bad-request"));
        return problem;
    }

    /** Whether the topmost frame of this throw is this project's own code. */
    private static boolean raisedByThisProject(Throwable ex) {
        StackTraceElement[] frames = ex.getStackTrace();
        return frames.length > 0 && frames[0].getClassName().startsWith("dev.mosaicast.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
        // Never swallow a 500 silently — log the full cause for diagnosis (payload stays generic).
        log.error("Unhandled exception handling request", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred.");
        return problem;
    }
}
