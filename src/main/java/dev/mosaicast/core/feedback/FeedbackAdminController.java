// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.siteaccess.PassFloor;
import dev.mosaicast.core.web.PagedResponse;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reader side of feedback (ARCHITECTURE §20.3): the inbox, answering, forwarding and blocking, for whoever
 * meets the operator's floor; and the settings, for admins.
 *
 * <p>The filter chain opens {@code /api/admin/feedback/**} to podcasters and admins — the widest a floor can be —
 * and every handler here checks the floor actually set, which is the arrangement plugin config already uses. A
 * reader sees everything a report holds, the contact included; the settings page says so.
 */
@RestController
public class FeedbackAdminController {

    private final FeedbackService feedback;
    private final SiteConfigService site;

    public FeedbackAdminController(FeedbackService feedback, SiteConfigService site) {
        this.feedback = feedback;
        this.site = site;
    }

    @GetMapping("/api/admin/feedback")
    public PagedResponse<FeedbackView> inbox(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String kind,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size,
                                             Authentication authentication) {
        requireReader(authentication);
        return PagedResponse.of(feedback.inbox(FeedbackStatus.parse(status), FeedbackKind.parse(kind),
                PagedResponse.page(page), PagedResponse.size(size)), this::view);
    }

    /** One report; opening it marks it seen. */
    @GetMapping("/api/admin/feedback/{id}")
    public FeedbackView open(@PathVariable UUID id, Authentication authentication) {
        requireReader(authentication);
        return view(feedback.open(id));
    }

    public record StatusChange(String status, List<UUID> ids) {
    }

    @PutMapping("/api/admin/feedback/{id}/status")
    public FeedbackView setStatus(@PathVariable UUID id, @RequestBody StatusChange change,
                                  Authentication authentication) {
        UUID reader = requireReader(authentication);
        feedback.setStatus(List.of(id), status(change.status()), reader);
        return view(feedback.open(id));
    }

    /** Several at once — bulk spam, mostly (§20.3). */
    @PostMapping("/api/admin/feedback/status")
    public Map<String, Integer> setStatuses(@RequestBody StatusChange change, Authentication authentication) {
        UUID reader = requireReader(authentication);
        List<UUID> ids = change.ids() == null ? List.of() : change.ids().stream().limit(500).toList();
        return Map.of("changed", feedback.setStatus(ids, status(change.status()), reader));
    }

    public record Reply(String text) {
    }

    @PostMapping("/api/admin/feedback/{id}/reply")
    public FeedbackView reply(@PathVariable UUID id, @RequestBody Reply reply, Authentication authentication) {
        UUID reader = requireReader(authentication);
        return view(feedback.reply(id, reply.text(), reader));
    }

    @GetMapping("/api/admin/feedback/{id}/forward")
    public FeedbackService.Forward forward(@PathVariable UUID id, Authentication authentication) {
        requireReader(authentication);
        return feedback.forward(id);
    }

    @PutMapping("/api/admin/feedback/senders/{userId}/block")
    public ResponseEntity<Void> block(@PathVariable UUID userId, Authentication authentication) {
        feedback.block(userId, true, requireReader(authentication));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/admin/feedback/senders/{userId}/block")
    public ResponseEntity<Void> unblock(@PathVariable UUID userId, Authentication authentication) {
        feedback.block(userId, false, requireReader(authentication));
        return ResponseEntity.noContent().build();
    }

    // --- settings (ADMIN, by the filter chain) ------------------------------------------------------------

    /** The feedback settings (§20.1). */
    public record Settings(String mode, String url, String floor) {
    }

    @GetMapping("/api/admin/feedback/settings")
    public Settings settings() {
        return settingsOf(site.get());
    }

    @PutMapping("/api/admin/feedback/settings")
    public Settings updateSettings(@RequestBody Settings change) {
        FeedbackMode mode = FeedbackMode.parse(change.mode());
        PassFloor floor;
        try {
            floor = PassFloor.valueOf(change.floor() == null ? "" : change.floor().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Readers must be admin or podcaster.");
        }
        String url = change.url() == null ? null : change.url().strip();
        if (mode == FeedbackMode.EXTERNAL && !externalUrl(url)) {
            throw new IllegalArgumentException("Give an https: or mailto: address for the external channel.");
        }
        return settingsOf(site.updateFeedback(mode, url, floor));
    }

    private static Settings settingsOf(SiteConfig config) {
        return new Settings(config.getFeedbackMode().wire(), config.getFeedbackUrl(),
                config.getFeedbackFloor().name().toLowerCase(Locale.ROOT));
    }

    /** {@code https:} with a host, or {@code mailto:} with an address — nothing that runs or stays on-site. */
    static boolean externalUrl(String url) {
        if (url == null || url.length() > 500) {
            return false;
        }
        if (url.startsWith("mailto:")) {
            return url.length() > "mailto:".length() + 2 && url.indexOf('@') > 0;
        }
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private UUID requireReader(Authentication authentication) {
        if (!feedback.canRead(CurrentUser.role(authentication))) {
            throw new AccessDeniedException("You do not read feedback on this site.");
        }
        return CurrentUser.id(authentication).orElseThrow();
    }

    private static FeedbackStatus status(String value) {
        return FeedbackStatus.parse(value)
                .filter(s -> s != FeedbackStatus.NEW)
                .orElseThrow(() -> new IllegalArgumentException("Status must be seen, done or spam."));
    }

    /**
     * A report as a reader sees it. The sender is a name and an id for blocking, never more of the account;
     * {@code contact} is only ever there for an anonymous sender whose report is still open.
     */
    public record FeedbackView(UUID id, String kind, String message, Map<String, Object> context, String status,
                               Instant createdAt, String locale, String contact, Sender sender, Instant repliedAt,
                               Instant handledAt) {
    }

    public record Sender(UUID id, String displayName, boolean blocked) {
    }

    private FeedbackView view(Feedback report) {
        Optional<User> user = feedback.sender(report);
        Sender sender = user.map(u -> new Sender(u.getId(), u.getDisplayName(), u.getFeedbackBlockedAt() != null))
                .orElse(null);
        return new FeedbackView(report.getId(), report.getKind().wire(), report.getMessage(), report.getContext(),
                report.getStatus().wire(), report.getCreatedAt(), report.getLocale(), report.getContact(), sender,
                report.getRepliedAt(), report.getHandledAt());
    }
}
