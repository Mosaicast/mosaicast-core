// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.log.AppLogEntry;
import dev.mosaicast.core.log.AppLogLevel;
import dev.mosaicast.core.log.AppLogService;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.TooManyRequestsException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a plugin report something to the operator (ARCHITECTURE §7.8, §13). Until now a plugin frontend that
 * hit a problem could only log to the browser console, where no operator will ever see it; core's own logs
 * describe the host's view of a plugin, not the plugin's view of itself.
 *
 * <p>This is a write endpoint, so it is gated exactly like the doc store: the plugin must be <em>active</em>
 * (a switched-off plugin gets a 404 like everything else it serves), and the caller must be signed in at the
 * plugin's write floor. Size caps and a per-plugin rate limit keep one looping component from filling the
 * table — the log has to stay readable precisely when something is misbehaving.
 *
 * <p>The shell's {@code ctx.log(level, message)} posts here, and backend plugins reach the same log through
 * {@code ctx.logger()} in-process — that path needs no HTTP and is not rate-limited by this class.
 */
@RestController
public class PluginLogController {

    /** A structured context bigger than this is a payload, not a log line. */
    private static final int MAX_CONTEXT_CHARS = 4_000;

    private final PluginLoaderService plugins;
    private final AppLogService logs;
    private final PluginLogRateLimiter rateLimiter;

    public PluginLogController(PluginLoaderService plugins, AppLogService logs,
                               PluginLogRateLimiter rateLimiter) {
        this.plugins = plugins;
        this.logs = logs;
        this.rateLimiter = rateLimiter;
    }

    /** What a plugin reports. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PluginLogRequest(String level, String message, String detail, JsonNode context) {
    }

    @PostMapping("/api/plugins/{id}/log")
    public ResponseEntity<Void> log(@PathVariable String id, @RequestBody PluginLogRequest request,
                                    Authentication authentication) {
        PluginManifest manifest = plugins.active(id)
                .map(PluginRegistration::manifest)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        if (!PluginAccessPolicy.canWrite(manifest, CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to write plugin logs: " + id);
        }

        AppLogLevel level = AppLogLevel.parse(request.level())
                .orElseThrow(() -> new IllegalArgumentException(
                        "level must be one of ERROR, WARN, INFO, DEBUG"));
        String message = request.message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > AppLogEntry.MAX_MESSAGE) {
            throw new IllegalArgumentException(
                    "message must be at most " + AppLogEntry.MAX_MESSAGE + " characters");
        }
        if (request.detail() != null && request.detail().length() > AppLogEntry.MAX_DETAIL) {
            throw new IllegalArgumentException(
                    "detail must be at most " + AppLogEntry.MAX_DETAIL + " characters");
        }
        JsonNode context = request.context();
        if (context != null && context.toString().length() > MAX_CONTEXT_CHARS) {
            throw new IllegalArgumentException("context must be at most " + MAX_CONTEXT_CHARS + " characters");
        }

        switch (rateLimiter.check(id)) {
            case ALLOWED -> logs.record(level, "plugin", "frontend", id, message, request.detail(), context);
            case THROTTLED_FIRST -> {
                logs.record(AppLogLevel.WARN, "plugin", "frontend", id,
                        "Log entries from this plugin are being throttled (rate limit reached)", null, null);
                throw new TooManyRequestsException("Log rate limit reached for plugin: " + id);
            }
            case THROTTLED -> throw new TooManyRequestsException("Log rate limit reached for plugin: " + id);
            default -> throw new IllegalStateException();
        }
        return ResponseEntity.noContent().build();
    }
}
