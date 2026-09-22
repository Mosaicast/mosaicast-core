// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes an RFC 9457 {@code application/problem+json} body from outside the {@code @ControllerAdvice}
 * (ARCHITECTURE §10).
 *
 * <p>Application errors have always answered in this shape; errors raised in the <em>filter chain</em> did
 * not. A 401 from the authentication entry point and a 403 from the access-denied handler fell through to
 * Spring Boot's default error page, so a client had two formats to parse for the same kind of answer —
 * and the second one advertises the framework (core#187):
 *
 * <pre>{@code {"timestamp":"…","status":403,"error":"Forbidden","path":"/api/me"}}</pre>
 *
 * <p>Deliberately hand-written rather than routed through the exception handler. By the time a filter
 * refuses a request there is no handler to dispatch to, and re-entering the MVC stack to render an error is
 * a larger machine than the four fields need — the rate limiter has produced this shape by hand since it
 * was written, for the same reason.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    /**
     * Writes {@code status} as a problem document.
     *
     * @param type   the problem type, appended to {@code https://mosaicast.dev/problems/}
     * @param detail human-readable, and deliberately vague about *why* a refusal happened: these two
     *               statuses are exactly the ones an attacker probes, and a precise answer is an oracle
     */
    public static void write(HttpServletResponse response, HttpStatus status, String type, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // JSON is UTF-8. Without this the servlet default (ISO-8859-1) is advertised, which is wrong today
        // for a body that happens to be ASCII and corrupting on the first non-ASCII character anyone adds.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"https://mosaicast.dev/problems/%s",\
                "title":"%s","status":%d,"detail":"%s"}"""
                .formatted(type, status.getReasonPhrase(), status.value(), detail));
    }
}
