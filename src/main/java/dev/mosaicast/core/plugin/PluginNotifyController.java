// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.NotificationException;
import dev.mosaicast.plugin.api.NotifyMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
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
 *
 * <p><strong>And authenticated is not by itself permission.</strong> This is a write, so it takes the
 * plugin's declared {@code data.writableBy} floor like the doc, blob, tag and log surfaces do. Without it
 * any signed-in fan could put arbitrary text and a link into the inbox of every participant the plugin
 * holds a {@code USER} partition for, in the site's own voice.
 */
@RestController
public class PluginNotifyController {

    private final PluginLoaderService plugins;

    public PluginNotifyController(PluginLoaderService plugins) {
        this.plugins = plugins;
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
    public List<UUID> notify(@PathVariable String id, @Valid @RequestBody SendRequest request,
                             Authentication authentication)
            throws NotificationException {
        PluginRegistration registration = plugins.active(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        // One 404 for "switched off" and "never declared it", as on every other declared surface: telling
        // them apart would let a page probe an install's manifest set.
        if (!registration.manifest().declaresNotifications()) {
            throw new NotFoundException("Unknown plugin: " + id);
        }
        // After the 404, deliberately: a caller with no business in this plugin's data should not learn
        // from a distinct 403 which plugins on this install declare notifications.
        if (!PluginAccessPolicy.canWrite(registration.manifest(), CurrentUser.role(authentication))) {
            throw new AccessDeniedException("Not allowed to send notifications as plugin '%s'"
                    .formatted(registration.manifest().id()));
        }
        // Constructed here rather than injected, so the SDK's own validation — a non-blank sentence per
        // locale, and an `en` entry — runs on browser input exactly as it does on a backend call.
        NotifyMessage message = new NotifyMessage(request.text(), request.link());
        // The notifier the plugin's own backend holds. Built here per request, this was a second
        // construction site — which is how it came to lack the write floor in the first place (core#201).
        return plugins.notifierOf(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id))
                .send(request.userIds(), message);
    }
}
