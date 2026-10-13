// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import dev.mosaicast.core.auth.SignupGate;
import dev.mosaicast.core.web.CodedBadRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the site-access policy (ARCHITECTURE §18.1) and answers the one question core enforces
 * itself: whether a first login may create an account (§18.3).
 */
@Service
public class SiteAccessService implements SignupGate {

    /** How far ahead a scheduled opening must lie when saved, so a visitor's countdown never jumps. */
    static final Duration MIN_LEAD = Duration.ofSeconds(60);

    /**
     * Where "open now" puts the opening: far enough out that the last ten seconds still play after the edge
     * has picked the change up, close enough that it reads as now.
     */
    static final Duration OPEN_NOW_LEAD = Duration.ofSeconds(12);

    private static final Logger log = LoggerFactory.getLogger(SiteAccessService.class);

    private final SiteAccessRepository repository;
    private final Clock clock;
    private final EdgeHeartbeat heartbeat;

    public SiteAccessService(SiteAccessRepository repository, Clock clock, EdgeHeartbeat heartbeat) {
        this.repository = repository;
        this.clock = clock;
        this.heartbeat = heartbeat;
    }

    /** The current policy; an install that never saved one is open. */
    @Transactional(readOnly = true)
    public SiteAccess current() {
        return repository.findById(SiteAccess.SINGLETON_ID).orElseGet(SiteAccess::openByDefault);
    }

    /** The current instant, by the clock every decision here is made on. */
    public Instant now() {
        return clock.instant();
    }

    /** A requested change to the policy; every field is the complete new value. */
    public record Change(SiteState state, @Nullable Instant opensAt, @Nullable Instant endsAt,
                         PassFloor passFloor, boolean staffLoginVisible, boolean signupsWhileClosed,
                         boolean edgeCounting) {
    }

    /**
     * Saves a new policy.
     *
     * @param change the complete new policy
     * @param actor  who is saving it, for the log
     * @return the saved policy
     * @throws CodedBadRequest when a time does not fit the state
     */
    @Transactional
    public SiteAccess update(Change change, @Nullable UUID actor) {
        Instant now = clock.instant();
        Instant opensAt = null;
        Instant endsAt = null;
        switch (change.state()) {
            case SCHEDULED -> {
                if (change.opensAt() == null) {
                    throw new CodedBadRequest("siteAccess.opensAt.missing", "A scheduled opening needs a time.");
                }
                if (change.opensAt().isBefore(now.plus(MIN_LEAD))) {
                    throw new CodedBadRequest("siteAccess.opensAt.tooSoon",
                            "The opening must be at least " + MIN_LEAD.toSeconds()
                                    + " seconds away. Use \"Open now\" to open right away.");
                }
                opensAt = change.opensAt();
            }
            case MAINTENANCE -> {
                if (change.endsAt() != null && !change.endsAt().isAfter(now)) {
                    throw new CodedBadRequest("siteAccess.endsAt.past", "The expected end must be in the future.");
                }
                endsAt = change.endsAt();
            }
            case OPEN, CLOSED -> {
                // Neither has a time; one sent along is dropped rather than stored to mean nothing.
            }
        }
        return save(new Change(change.state(), opensAt, endsAt, change.passFloor(), change.staffLoginVisible(),
                change.signupsWhileClosed(), change.edgeCounting()), now, actor);
    }

    /**
     * Opens the site in {@link #OPEN_NOW_LEAD}: a scheduled opening the edge plays the final seconds for,
     * rather than an instant switch that would cut a waiting visitor's countdown off mid-number.
     */
    @Transactional
    public SiteAccess openNow(@Nullable UUID actor) {
        Instant now = clock.instant();
        SiteAccess current = current();
        return save(new Change(SiteState.SCHEDULED, now.plus(OPEN_NOW_LEAD), null, current.getPassFloor(),
                current.isStaffLoginVisible(), current.isSignupsWhileClosed(), current.isEdgeCounting()), now,
                actor);
    }

    private SiteAccess save(Change change, Instant now, @Nullable UUID actor) {
        SiteAccess access = repository.findById(SiteAccess.SINGLETON_ID).orElseGet(SiteAccess::openByDefault);
        List<String> changes = describe(access, change);
        if (access.isEdgeCounting() && !change.edgeCounting()) {
            heartbeat.forgetWaiting();
        }
        access.apply(change.state(), change.opensAt(), change.endsAt(), change.passFloor(),
                change.staffLoginVisible(), change.signupsWhileClosed(), change.edgeCounting(), now);
        SiteAccess saved = repository.save(access);
        // Who and what, old → new: closing a site is the kind of change someone asks about a week later.
        log.info("Site access changed by {}: {}", actor, changes.isEmpty() ? "saved unchanged" : String.join(", ", changes));
        return saved;
    }

    private static List<String> describe(SiteAccess before, Change after) {
        List<String> changes = new ArrayList<>();
        if (before.getState() != after.state()) {
            changes.add("state %s → %s".formatted(before.getState(), after.state()));
        }
        if (!Objects.equals(before.getOpensAt(), after.opensAt())) {
            changes.add("opens %s → %s".formatted(before.getOpensAt(), after.opensAt()));
        }
        if (!Objects.equals(before.getEndsAt(), after.endsAt())) {
            changes.add("ends %s → %s".formatted(before.getEndsAt(), after.endsAt()));
        }
        if (before.getPassFloor() != after.passFloor()) {
            changes.add("pass floor %s → %s".formatted(before.getPassFloor(), after.passFloor()));
        }
        if (before.isStaffLoginVisible() != after.staffLoginVisible()) {
            changes.add("staff login link %s → %s".formatted(before.isStaffLoginVisible(), after.staffLoginVisible()));
        }
        if (before.isEdgeCounting() != after.edgeCounting()) {
            changes.add("edge counting %s → %s".formatted(before.isEdgeCounting(), after.edgeCounting()));
        }
        if (before.isSignupsWhileClosed() != after.signupsWhileClosed()) {
            changes.add("sign-ups while closed %s → %s"
                    .formatted(before.isSignupsWhileClosed(), after.signupsWhileClosed()));
        }
        return changes;
    }

    /**
     * Whether a first login may create an account now (§18.3): always while the site is effectively open,
     * otherwise only when an admin has switched sign-ups on.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean allowsNewAccounts() {
        SiteAccess access = current();
        return access.effectiveState(clock.instant()) == SiteState.OPEN || access.isSignupsWhileClosed();
    }
}
