// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.notification.NotificationService;
import dev.mosaicast.plugin.api.NotificationException;
import dev.mosaicast.plugin.api.NotifyMessage;
import dev.mosaicast.plugin.api.Notifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One plugin's door into the inbox (ARCHITECTURE §17.1).
 *
 * <p>This is the <strong>only plugin surface that writes into another user's experience</strong> —
 * everything else a plugin touches is its own scope or the current visitor's (§7.6). Unbounded it is a
 * spam cannon pointed at the whole user list, so almost everything here is a bound:
 *
 * <ul>
 *   <li><strong>Eligibility.</strong> Only users this plugin already holds {@code USER}-scope data for,
 *       read against the same partitions {@code queryAcrossUsers} spans. Bingo reaches its participants
 *       because participants have rows; nothing reaches a user who never touched the plugin.</li>
 *   <li><strong>Rate limits are the host's.</strong> Per recipient per window, plus a ceiling across all
 *       recipients. A limit a plugin enforces is a limit a plugin can drop.</li>
 *   <li><strong>Links are internal.</strong> A notification is chrome the site speaks through, so a plugin
 *       that could aim one off-site could phish the site's own users in the site's own voice.</li>
 * </ul>
 *
 * <p><strong>Ineligible recipients are dropped, not rejected.</strong> An erased account (§12.8) is the
 * ordinary case, so one stale participant must not cost the other forty-nine their notification — and
 * answering "that user exists but is not yours" would tell a plugin something it should not be able to
 * ask. The ids that were written come back instead.
 */
public class NotifierImpl implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NotifierImpl.class);

    private final PluginManifest manifest;
    private final String pluginId;
    private final PluginDataRepository data;
    private final NotificationService notifications;
    private final PluginNotifyRateLimiter limiter;

    public NotifierImpl(PluginManifest manifest, PluginDataRepository data,
                        NotificationService notifications, PluginNotifyRateLimiter limiter) {
        this.manifest = manifest;
        this.pluginId = manifest.id();
        this.data = data;
        this.notifications = notifications;
        this.limiter = limiter;
    }

    @Override
    public List<UUID> send(Collection<UUID> userIds, NotifyMessage message)
            throws NotificationException {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        String link = validateLink(pluginId, message.link());

        Set<UUID> asked = userIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> eligible = data.userScopesHeldBy(pluginId, DataScope.USER_TYPE,
                asked.stream().map(UUID::toString).collect(Collectors.toSet()));

        // The batch ceiling is checked before any row is written, so a send is not half-applied when the
        // plugin is over its allowance — a scheduled sender retrying a partially-delivered batch would
        // notify the first half twice.
        limiter.checkBatch(pluginId, eligible.size());
        // The smaller of what this plugin's manifest asked for and what the operator permits.
        int allowance = limiter.perRecipientAllowance(manifest);

        List<UUID> notified = new ArrayList<>();
        for (UUID userId : asked) {
            if (!eligible.contains(userId.toString())) {
                continue;
            }
            if (!limiter.tryRecipient(pluginId, userId, allowance)) {
                // Per-recipient exhaustion is a property of that one user, not of the batch: the others
                // still get theirs, and the caller sees the absence in the returned ids.
                continue;
            }
            notifications.fromPlugin(userId, pluginId, message.text(), link);
            notified.add(userId);
        }
        if (notified.size() < asked.size()) {
            log.debug("Plugin {} notified {} of {} requested users", pluginId, notified.size(), asked.size());
        }
        return List.copyOf(notified);
    }

    /**
     * Checks a link is somewhere on this site (§17.1).
     *
     * <p>Accepts a path under this plugin's own {@code /p/<pluginId>/} subtree — bare or fully qualified —
     * or one of core's own absolute paths. Anything with a scheme or an authority is refused, including
     * protocol-relative {@code //evil.example}, which is a URL that looks like a path.
     *
     * @return the normalised absolute path, or null when there was no link
     */
    static String validateLink(String pluginId, String link) throws NotificationException {
        if (link == null || link.isBlank()) {
            return null;
        }
        String value = link.strip();
        if (value.contains("://") || value.startsWith("//") || value.contains("\\")
                || value.contains(":") || value.contains("..")) {
            throw new NotificationException(NotificationException.Reason.INVALID_LINK,
                    "A notification link must point inside this site");
        }
        String path = value.startsWith("/") ? value : "/p/" + pluginId + "/" + value;
        if (!path.startsWith("/")) {
            throw new NotificationException(NotificationException.Reason.INVALID_LINK,
                    "A notification link must be an absolute path");
        }
        // A plugin may point at its own subtree or at a core page, never at another plugin's — the latter
        // would let one plugin drive traffic into a surface it does not own.
        //
        // The plugin's own root counts as its own: `/p/<id>` with no trailing slash is where its default nav
        // entry and the first entries of both shipped plugins point, and it was refused as "foreign" (core#201).
        String root = "/p/" + pluginId;
        boolean own = path.equals(root) || path.startsWith(root + "/") || path.startsWith(root + "?")
                || path.startsWith(root + "#");
        if (path.startsWith("/p/") && !own) {
            throw new NotificationException(NotificationException.Reason.INVALID_LINK,
                    "A notification link may only point at this plugin's own pages");
        }
        return path;
    }
}
