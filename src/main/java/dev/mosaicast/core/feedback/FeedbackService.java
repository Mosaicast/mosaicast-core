// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.notification.NotificationKind;
import dev.mosaicast.core.notification.NotificationService;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.core.siteaccess.PassFloor;
import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.FeedbackRefusedException;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Role;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visitor feedback (ARCHITECTURE §20): takes reports, keeps spam out, and lets readers work the inbox.
 *
 * <p><strong>Layers, none of which has to be perfect.</strong> A report passes, in order: the mode, a block on the
 * sender, its own shape (kind, length, links), the honeypot, the proof of work (anonymous senders only), the
 * sender's rate limit, the anonymous ceiling (anonymous only — a flood of anonymous spam must not silence people
 * the site can identify), and the duplicate check. The honeypot and a duplicate are answered as accepted and
 * dropped, so neither teaches a bot anything.
 *
 * <p><strong>The context is the server's.</strong> The browser proposes a route, the plugins on the page and an
 * error code; this class keeps what checks out and adds the versions itself — a client that could write a
 * version could file a report about a release that is not running.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /**
     * The error codes a report may carry: the sign-in failures {@code OAuthLoginFailureHandler} sends, plus the
     * shell's own error states. Anything else is dropped rather than stored.
     */
    static final Set<String> ERROR_CODES = Set.of(
            "account_conflict", "link_required", "signups_closed", "failed", "crash", "not-found", "load-failed");

    /** Where a forwarded report goes when no plugin is named: this project's own tracker. */
    static final String CORE_TRACKER = "https://github.com/Mosaicast/mosaicast-core/issues/new";

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final int MAX_ROUTE = 300;
    private static final int MAX_PLUGINS = 10;
    private static final Duration BUSY_RETRY = Duration.ofMinutes(10);

    private final FeedbackRepository reports;
    private final FeedbackProperties properties;
    private final ProofOfWork proofOfWork;
    private final SiteConfigService site;
    private final UserRepository users;
    private final PluginLoaderService plugins;
    private final NotificationService notifications;
    private final Clock clock;
    private final String version;
    private final FixedWindowRateLimiter perAddress = new FixedWindowRateLimiter();
    private final FixedWindowRateLimiter perUser = new FixedWindowRateLimiter();

    public FeedbackService(FeedbackRepository reports, FeedbackProperties properties, ProofOfWork proofOfWork,
                           SiteConfigService site, UserRepository users, PluginLoaderService plugins,
                           NotificationService notifications, Clock clock,
                           @Value("${mosaicast.version:dev}") String version) {
        this.reports = reports;
        this.properties = properties;
        this.proofOfWork = proofOfWork;
        this.site = site;
        this.users = users;
        this.plugins = plugins;
        this.notifications = notifications;
        this.clock = clock;
        this.version = version;
    }

    /** What the browser sends. {@code website} is the honeypot: no person fills it in. */
    public record Submission(String kind, String message, String contact, String locale, ProposedContext context,
                             String challenge, String solution, String website) {
    }

    /** What the browser proposes about where the report came from. Every part is checked. */
    public record ProposedContext(String route, List<String> plugins, String error) {
    }

    // --- sending ------------------------------------------------------------------------------------------

    /** A challenge for an anonymous sender, or 404 when the form is not open to them. */
    public ProofOfWork.Challenge challenge() {
        if (site.get().getFeedbackMode() != FeedbackMode.EVERYONE) {
            throw new NotFoundException("Feedback is not open to anonymous senders here.");
        }
        return proofOfWork.issue();
    }

    /**
     * Takes a report, or refuses it with one of §20.4's problem types.
     *
     * @param userId  the signed-in sender, or empty for anonymous
     * @param address the client address, for an anonymous sender's rate limit only — never stored
     */
    @Transactional
    public void submit(Submission in, Optional<UUID> userId, String address) {
        SiteConfig config = site.get();
        FeedbackMode mode = config.getFeedbackMode();
        if (!mode.acceptsReports()) {
            throw new NotFoundException("Feedback is not taken here.");
        }
        boolean anonymous = userId.isEmpty();
        if (anonymous && mode == FeedbackMode.SIGNED_IN) {
            throw new AuthenticationCredentialsNotFoundException("Sign in to send feedback.");
        }
        if (userId.isPresent()) {
            users.findById(userId.get())
                    .filter(user -> user.getFeedbackBlockedAt() != null)
                    .ifPresent(user -> {
                        throw FeedbackRefusedException.blocked();
                    });
        }

        FeedbackKind kind = FeedbackKind.parse(in.kind())
                .orElseThrow(() -> FeedbackRefusedException.invalid("Pick what this is about."));
        String message = in.message() == null ? "" : in.message().strip();
        if (message.isEmpty()) {
            throw FeedbackRefusedException.invalid("Write a few words first.");
        }
        if (message.length() > properties.maxMessage()) {
            throw FeedbackRefusedException.invalid(
                    "Keep it under %d characters.".formatted(properties.maxMessage()));
        }
        if (anonymous && countUrls(message) > properties.maxUrls()) {
            throw FeedbackRefusedException.invalid(
                    "Use at most %d links, or sign in.".formatted(properties.maxUrls()));
        }
        String contact = anonymous && in.contact() != null && !in.contact().isBlank()
                ? in.contact().strip() : null;
        if (contact != null && contact.length() > properties.maxContact()) {
            throw FeedbackRefusedException.invalid("That contact is too long.");
        }

        if (in.website() != null && !in.website().isBlank()) {
            // A field no person sees: answered as accepted, so a bot learns nothing from it.
            log.debug("Feedback honeypot filled; dropped");
            return;
        }
        if (anonymous && proofOfWork.verify(in.challenge(), in.solution()) != null) {
            throw FeedbackRefusedException.challengeFailed();
        }

        Instant now = clock.instant();
        FixedWindowRateLimiter.Decision decision = anonymous
                ? perAddress.check(address == null ? "unknown" : address, properties.anonymousPerWindow(),
                        properties.window(), now)
                : perUser.check(userId.get().toString(), properties.signedInPerWindow(), properties.window(), now);
        if (!decision.allowed()) {
            throw FeedbackRefusedException.rateLimited(decision.retryAfter());
        }
        if (anonymous && (reports.countOpenAnonymous() >= properties.anonymousOpenCeiling()
                || reports.countAnonymousSince(now.minus(Duration.ofHours(1))) >= properties.anonymousPerHourCeiling())) {
            throw FeedbackRefusedException.busy(BUSY_RETRY);
        }

        String hash = dedupHash(kind, message);
        if (reports.existsByDedupHashAndCreatedAtAfter(hash, now.minus(properties.dedupWindow()))) {
            // A double-click is harmless and a repeat gains nothing: one report, one answer.
            return;
        }

        Feedback report = new Feedback(kind, message, context(in.context()), userId.orElse(null), contact,
                locale(in.locale()), hash);
        reports.save(report);
        tellReaders(config.getFeedbackFloor(), userId.orElse(null));
        log.info("Feedback {} received ({}, {})", report.getId(), kind.wire(), anonymous ? "anonymous" : "signed in");
    }

    // --- reading ------------------------------------------------------------------------------------------

    /** Whether this role reads reports under the current floor. */
    public boolean canRead(Optional<Role> role) {
        return role.map(r -> site.get().getFeedbackFloor().admits(r)).orElse(false);
    }

    @Transactional(readOnly = true)
    public Page<Feedback> inbox(Optional<FeedbackStatus> status, Optional<FeedbackKind> kind, int page, int size) {
        return reports.inbox(status.map(FeedbackStatus::wire).orElse(null), kind.map(FeedbackKind::wire).orElse(null),
                PageRequest.of(page, size));
    }

    /** One report, marked seen if it was new. */
    @Transactional
    public Feedback open(UUID id) {
        Feedback report = find(id);
        report.markSeen();
        return reports.save(report);
    }

    /** Moves reports to a status; closing them clears their contacts. Answers how many moved. */
    @Transactional
    public int setStatus(List<UUID> ids, FeedbackStatus status, UUID reader) {
        Instant now = clock.instant();
        List<Feedback> found = reports.findByIdIn(ids);
        found.forEach(report -> report.moveTo(status, reader, now));
        reports.saveAll(found);
        return found.size();
    }

    /**
     * Answers a signed-in sender in-app (§20.3). An anonymous sender has no inbox; their contact is answered from
     * the reader's own mail client.
     */
    @Transactional
    public Feedback reply(UUID id, String text, UUID reader) {
        Feedback report = find(id);
        if (report.getUserId() == null) {
            throw new ConflictException("This sender isn't signed in. Answer them by email.");
        }
        if (text == null || text.isBlank()) {
            throw FeedbackRefusedException.invalid("Write an answer first.");
        }
        notifications.feedbackReply(report.getUserId(), text, reader, null);
        report.markReplied(reader, clock.instant());
        return reports.save(report);
    }

    /**
     * The text a reader forwards to a project's tracker (§20.3): the kind, the message, the context and the
     * versions — never the contact or anything naming the sender. Core sends nothing; the reader's browser opens
     * the tracker and they edit before submitting.
     */
    @Transactional(readOnly = true)
    public Forward forward(UUID id) {
        Feedback report = find(id);
        StringBuilder text = new StringBuilder();
        text.append("Kind: ").append(report.getKind().wire()).append('\n');
        Map<String, Object> context = report.getContext();
        if (context.get("route") != null) {
            text.append("Page: ").append(context.get("route")).append('\n');
        }
        if (context.get("error") != null) {
            text.append("Error: ").append(context.get("error")).append('\n');
        }
        text.append("Core: ").append(context.getOrDefault("core", "unknown")).append('\n');
        String tracker = CORE_TRACKER;
        if (context.get("plugins") instanceof List<?> named && !named.isEmpty()) {
            for (Object entry : named) {
                if (entry instanceof Map<?, ?> plugin) {
                    text.append("Plugin: ").append(plugin.get("id")).append(' ').append(plugin.get("version"))
                            .append('\n');
                }
            }
            if (named.size() == 1 && named.get(0) instanceof Map<?, ?> only && only.get("homepage") instanceof String home
                    && home.startsWith("https://")) {
                tracker = home;
            }
        }
        text.append('\n').append(report.getMessage()).append('\n');
        return new Forward(text.toString(), tracker);
    }

    /** What forwarding hands the reader: the summary to paste, and where. */
    public record Forward(String text, String tracker) {
    }

    /** Blocks a signed-in user from sending, or lifts it — logged like a role change (§8.5). */
    @Transactional
    public void block(UUID userId, boolean blocked, UUID reader) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("No such user: " + userId));
        user.blockFeedback(blocked ? clock.instant() : null);
        users.save(user);
        log.info("Feedback from '{}' ({}) {} by {}", user.getDisplayName(), userId,
                blocked ? "blocked" : "unblocked", reader);
    }

    /** Who sent a report, for a reader: a name and whether they are blocked; empty for anonymous. */
    @Transactional(readOnly = true)
    public Optional<User> sender(Feedback report) {
        return report.getUserId() == null ? Optional.empty() : users.findById(report.getUserId());
    }

    // --- lifecycle ----------------------------------------------------------------------------------------

    /** Closed reports past retention go (§20.5). */
    @Transactional
    public int purgeClosed() {
        return reports.purgeClosedBefore(clock.instant().minus(properties.retention()));
    }

    /** A person's own reports, for their export (§12.8.1). */
    @Transactional(readOnly = true)
    public List<Feedback> reportsBy(UUID userId) {
        return reports.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public long eraseFor(UUID userId) {
        return reports.deleteByUserId(userId);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpiredWindows() {
        Instant now = clock.instant();
        perAddress.evictExpired(properties.window(), now);
        perUser.evictExpired(properties.window(), now);
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private Feedback find(UUID id) {
        return reports.findById(id).orElseThrow(() -> new NotFoundException("No such report: " + id));
    }

    private void tellReaders(PassFloor floor, UUID sender) {
        List<Role> roles = floor == PassFloor.PODCASTER ? List.of(Role.ADMIN, Role.PODCASTER) : List.of(Role.ADMIN);
        for (User reader : users.findByRoleIn(roles)) {
            if (!reader.getId().equals(sender)) {
                notifications.coalesce(reader.getId(), NotificationKind.FEEDBACK_NEW, "/admin/feedback");
            }
        }
    }

    /**
     * The context as stored: a route without its query or fragment (those can carry anything), only plugins that
     * are installed and switched on, with their versions from the server, an error code from the fixed list, and
     * core's version.
     */
    Map<String, Object> context(ProposedContext proposed) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("core", version);
        if (proposed == null) {
            return context;
        }
        String route = proposed.route();
        if (route != null && route.startsWith("/")) {
            int cut = route.length();
            for (char mark : new char[] {'?', '#'}) {
                int at = route.indexOf(mark);
                if (at >= 0) {
                    cut = Math.min(cut, at);
                }
            }
            String path = route.substring(0, cut);
            context.put("route", path.length() > MAX_ROUTE ? path.substring(0, MAX_ROUTE) : path);
        }
        if (proposed.plugins() != null) {
            List<Map<String, String>> named = new ArrayList<>();
            for (String id : proposed.plugins().stream().limit(MAX_PLUGINS).distinct().toList()) {
                if (id == null || !PLUGIN_ID.matcher(id).matches()) {
                    continue;
                }
                plugins.active(id).map(PluginRegistration::manifest).ifPresent(manifest -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", manifest.id());
                    entry.put("version", manifest.version() == null ? "" : manifest.version());
                    if (manifest.homepage() != null) {
                        entry.put("homepage", manifest.homepage());
                    }
                    named.add(entry);
                });
            }
            if (!named.isEmpty()) {
                context.put("plugins", named);
            }
        }
        if (proposed.error() != null && ERROR_CODES.contains(proposed.error())) {
            context.put("error", proposed.error());
        }
        return context;
    }

    private static String locale(String locale) {
        return locale != null && locale.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?") ? locale : null;
    }

    static int countUrls(String message) {
        Matcher m = URL.matcher(message);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Case, spacing and surrounding punctuation do not make a report new. */
    static String dedupHash(FeedbackKind kind, String message) {
        String normal = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("^[\\p{P}\\s]+|[\\p{P}\\s]+$", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((kind.wire() + "\n" + normal).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
