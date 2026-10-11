// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import dev.mosaicast.core.auth.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The site-access policy over HTTP (ARCHITECTURE §18.2): a public read the edge polls, and the admin
 * editor. The admin paths are ADMIN-only through the {@code /api/admin/**} rule in {@code SecurityConfig}.
 */
@RestController
public class SiteAccessController {

    /** Header carrying the shared token that marks a poll as the edge's. */
    static final String TOKEN_HEADER = "Mosaicast-Edge-Token";

    /** Header carrying the edge's own build label, shown on the admin page. */
    static final String BUILD_HEADER = "Mosaicast-Edge-Build";

    private final SiteAccessService access;
    private final EdgeHeartbeat heartbeat;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    public SiteAccessController(SiteAccessService access, EdgeHeartbeat heartbeat,
                                ObjectProvider<ClientRegistrationRepository> registrations) {
        this.access = access;
        this.heartbeat = heartbeat;
        this.registrations = registrations;
    }

    /** A sign-in provider the edge can link to: the same path core's own login button uses. */
    public record Provider(String id, String path) {
    }

    /** Whether the edge links its staff login from its pages, and where that login goes. */
    public record StaffLogin(boolean visible, List<Provider> providers) {
    }

    /**
     * The public policy (§18.2). Nothing in it is secret: the countdown shows the date anyway, and the
     * providers are the login buttons core already renders.
     *
     * @param state          what an admin saved
     * @param effectiveState the state as of {@code now} — a scheduled opening that has passed is {@code open}
     * @param now            core's clock, informational; the edge flips on its own
     */
    public record AccessView(String state, String effectiveState, @Nullable Instant opensAt,
                             @Nullable Instant endsAt, String passFloor, StaffLogin staffLogin, Instant now,
                             long revision) {
    }

    /** What the edge last reported, for the admin page. */
    public record EdgeView(String status, @Nullable Instant lastSeen, @Nullable String build) {
    }

    /** The admin editor's view: the public one plus the settings and the edge's liveness. */
    public record AdminAccessView(AccessView access, boolean signupsWhileClosed, @Nullable Instant updatedAt,
                                  EdgeView edge) {
    }

    /** An admin's complete new policy. Times are instants; the page converts from the admin's zone. */
    public record UpdateAccess(String state, @Nullable Instant opensAt, @Nullable Instant endsAt,
                               String passFloor, boolean staffLoginVisible, boolean signupsWhileClosed) {
    }

    @GetMapping("/api/site/access")
    public ResponseEntity<AccessView> publicView(WebRequest request,
                                                 @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                                 @RequestHeader(name = BUILD_HEADER, required = false) String build) {
        heartbeat.record(token, build);
        SiteAccess current = access.current();
        String etag = "\"" + current.getRevision() + "\"";
        // no-store: a cached "scheduled" served after the opening is a site that stays shut.
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noStore()).body(view(current));
    }

    @GetMapping("/api/admin/site/access")
    public AdminAccessView adminView() {
        return adminView(access.current());
    }

    @PutMapping("/api/admin/site/access")
    public AdminAccessView update(@RequestBody UpdateAccess request, Authentication authentication) {
        SiteAccessService.Change change = new SiteAccessService.Change(
                parse(SiteState.class, request.state(), "state"),
                request.opensAt(),
                request.endsAt(),
                parse(PassFloor.class, request.passFloor(), "passFloor"),
                request.staffLoginVisible(),
                request.signupsWhileClosed());
        return adminView(access.update(change, actor(authentication)));
    }

    @PostMapping("/api/admin/site/access/open-now")
    public AdminAccessView openNow(Authentication authentication) {
        return adminView(access.openNow(actor(authentication)));
    }

    private AdminAccessView adminView(SiteAccess current) {
        EdgeHeartbeat.Report report = heartbeat.report();
        return new AdminAccessView(view(current), current.isSignupsWhileClosed(), current.getUpdatedAt(),
                new EdgeView(lower(report.status()), report.lastSeen(), report.build()));
    }

    private AccessView view(SiteAccess current) {
        Instant now = access.now();
        return new AccessView(
                lower(current.getState()),
                lower(current.effectiveState(now)),
                current.getOpensAt(),
                current.getEndsAt(),
                lower(current.getPassFloor()),
                new StaffLogin(current.isStaffLoginVisible(), providers()),
                now,
                current.getRevision());
    }

    /**
     * The configured sign-in providers, from the same registrations Spring Security's login uses, so a
     * provider added to core appears here without a second list. None when no provider is configured.
     */
    private List<Provider> providers() {
        ClientRegistrationRepository repository = registrations.getIfAvailable();
        if (!(repository instanceof Iterable<?> all)) {
            return List.of();
        }
        List<Provider> providers = new java.util.ArrayList<>();
        for (Object item : all) {
            if (item instanceof ClientRegistration registration) {
                String id = registration.getRegistrationId();
                providers.add(new Provider(id, "/oauth2/authorization/" + id));
            }
        }
        return List.copyOf(providers);
    }

    private static @Nullable UUID actor(Authentication authentication) {
        return CurrentUser.id(authentication).orElse(null);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, @Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " has an unknown value");
        }
    }
}
