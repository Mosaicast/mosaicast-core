// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.notification.NotificationService;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.NotificationException;
import dev.mosaicast.plugin.api.NotifyMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser half of {@code ctx.notify} (ARCHITECTURE §17.1).
 *
 * <p>The backend twin is where nearly all real use lives — the thing worth announcing usually finishes on
 * a timer. This exists because some of it does not: a plugin resolving something in front of the person
 * who pressed the button still has to tell the others.
 *
 * <p><strong>Authenticated, unlike most plugin reads.</strong> Every other plugin surface that is open to
 * anonymous callers only ever hands data <em>out</em>; this one writes into other people's inboxes, and an
 * anonymous visitor has no business doing that however the manifest is written.
 */
@RestController
public class PluginNotifyController {

    private final PluginLoaderService plugins;
    private final PluginDataRepository data;
    private final NotificationService notifications;
    private final PluginNotifyRateLimiter limiter;

    public PluginNotifyController(PluginLoaderService plugins, PluginDataRepository data,
                                  NotificationService notifications, PluginNotifyRateLimiter limiter) {
        this.plugins = plugins;
        this.data = data;
        this.notifications = notifications;
        this.limiter = limiter;
    }

    /**
     * The send body.
     *
     * @param userIds who to tell
     * @param text    one finished sentence per locale code; must carry {@code en}
     * @param link    an internal path, or null
     */
    public record SendRequest(@NotEmpty List<UUID> userIds, @NotEmpty Map<String, String> text,
                              String link) {
    }

    /**
     * Sends one notification to each eligible user, and answers who actually got it.
     *
     * <p>Ineligible recipients are absent from the answer rather than rejecting the call — an erased
     * account is the ordinary case, and one stale participant must not cost the others their notification.
     *
     * @return the ids notified
     */
    @PostMapping("/api/plugins/{id}/notify")
    public List<UUID> notify(@PathVariable String id, @Valid @RequestBody SendRequest request)
            throws NotificationException {
        PluginRegistration registration = plugins.active(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        // One 404 for "switched off" and "never declared it", as on every other declared surface: telling
        // them apart would let a page probe an install's manifest set.
        if (!registration.manifest().declaresNotifications()) {
            throw new NotFoundException("Unknown plugin: " + id);
        }
        // Constructed here rather than injected, so the SDK's own validation — a non-blank sentence per
        // locale, and an `en` entry — runs on browser input exactly as it does on a backend call.
        NotifyMessage message = new NotifyMessage(request.text(), request.link());
        return new NotifierImpl(id, data, notifications, limiter).send(request.userIds(), message);
    }
}
