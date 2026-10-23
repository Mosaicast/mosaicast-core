// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * A plugin action the host answered without a result (platformApi 0.20.0, ARCHITECTURE §7.9): an unknown or
 * unwired name, a body over the cap, a plugin too busy or too slow to answer, or the plugin's own rejection.
 *
 * <p>One class with a factory per case rather than six, because the mapping is mechanical — each case knows
 * its status and its problem slug — while what must stay apart, the slugs, still does: the browser keys on
 * them, and "you called a name that does not exist" and "the plugin never wired that name" have different
 * fixes even though neither is the caller's fault in the same way.
 */
public final class PluginCallRefusedException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final Duration retryAfter;
    private final String code;

    private PluginCallRefusedException(HttpStatus status, String slug, String message, Duration retryAfter,
                                       String code) {
        super(message);
        this.status = status;
        this.slug = slug;
        this.retryAfter = retryAfter;
        this.code = code;
    }

    /** 404: the plugin declares actions, but not this name. */
    public static PluginCallRefusedException unknownAction(String pluginId, String name) {
        return new PluginCallRefusedException(HttpStatus.NOT_FOUND, "unknown-action",
                "Plugin '%s' declares no action '%s'.".formatted(pluginId, name), null, null);
    }

    /** 503: declared, but the backend registered no handler for it — the author's wiring, not the caller's. */
    public static PluginCallRefusedException unavailable(String pluginId, String name) {
        return new PluginCallRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "action-unavailable",
                "Plugin '%s' declares action '%s' but registered no handler for it.".formatted(pluginId, name),
                null, null);
    }

    /** 413: the body is over the cap, measured as UTF-8 JSON. */
    public static PluginCallRefusedException payloadTooLarge(int limitBytes) {
        return new PluginCallRefusedException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large",
                "The request body is over %d bytes.".formatted(limitBytes), null, null);
    }

    /** 503: every slot this plugin has is taken; the caller may retry. */
    public static PluginCallRefusedException busy(String pluginId, Duration retryAfter) {
        return new PluginCallRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "action-busy",
                "Plugin '%s' is busy; try again shortly.".formatted(pluginId), retryAfter, null);
    }

    /** 504: the handler did not answer within the deadline. */
    public static PluginCallRefusedException timeout(String pluginId, Duration deadline) {
        return new PluginCallRefusedException(HttpStatus.GATEWAY_TIMEOUT, "action-timeout",
                "Plugin '%s' did not answer within %d ms.".formatted(pluginId, deadline.toMillis()), null, null);
    }

    /** 422: the plugin looked at the call and said no, with a code its own UI can word. */
    public static PluginCallRefusedException rejected(String code, String detail) {
        return new PluginCallRefusedException(HttpStatus.UNPROCESSABLE_CONTENT, "action-rejected",
                detail == null ? code : detail, null, code);
    }

    /**
     * 500: the plugin's code threw something other than a rejection. Already logged to that plugin's log by
     * the time this is raised, so the handler does not log it again, and the caller learns nothing more than
     * any other 500 tells them.
     */
    public static PluginCallRefusedException failed(String pluginId) {
        return new PluginCallRefusedException(HttpStatus.INTERNAL_SERVER_ERROR, null,
                "An unexpected error occurred.", null, null);
    }

    public HttpStatus status() {
        return status;
    }

    /** The problem slug under {@code https://mosaicast.dev/problems/}, or null for the generic 500. */
    public String slug() {
        return slug;
    }

    /** When the caller may retry, or null when that is not the answer. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The plugin's rejection code, or null for every host refusal. */
    public String code() {
        return code;
    }
}
