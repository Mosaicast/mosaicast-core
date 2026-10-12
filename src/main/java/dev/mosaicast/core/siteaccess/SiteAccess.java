// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The site-access policy (ARCHITECTURE §18.1): one row, id pinned to {@code 1}, and <em>absent</em> until an
 * admin first saves it — no row means {@link SiteState#OPEN}, so an upgraded install changes nothing.
 */
@Entity
@Table(name = "site_access")
public class SiteAccess {

    /** The single-row id (the DB enforces {@code id = 1}). */
    public static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private SiteState state = SiteState.OPEN;

    @Column(name = "opens_at")
    private @Nullable Instant opensAt;

    @Column(name = "ends_at")
    private @Nullable Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_floor", nullable = false)
    private PassFloor passFloor = PassFloor.PODCASTER;

    @Column(name = "staff_login_visible", nullable = false)
    private boolean staffLoginVisible;

    @Column(name = "signups_while_closed", nullable = false)
    private boolean signupsWhileClosed;

    /** Increments on every write; the public read's ETag, so an unchanged poll is a 304. */
    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "updated_at")
    private @Nullable Instant updatedAt;

    protected SiteAccess() {
    }

    /** The policy of an install that never saved one: open, everything else at its default. */
    public static SiteAccess openByDefault() {
        return new SiteAccess();
    }

    /**
     * The state as of {@code now}: a {@link SiteState#SCHEDULED} policy whose moment has passed <em>is</em>
     * open, and is reported as such so no reader has to work it out again.
     */
    public SiteState effectiveState(Instant now) {
        if (state == SiteState.SCHEDULED && opensAt != null && !now.isBefore(opensAt)) {
            return SiteState.OPEN;
        }
        return state;
    }

    void apply(SiteState state, @Nullable Instant opensAt, @Nullable Instant endsAt, PassFloor passFloor,
               boolean staffLoginVisible, boolean signupsWhileClosed, Instant now) {
        this.state = state;
        this.opensAt = opensAt;
        this.endsAt = endsAt;
        this.passFloor = passFloor;
        this.staffLoginVisible = staffLoginVisible;
        this.signupsWhileClosed = signupsWhileClosed;
        this.revision++;
        this.updatedAt = now;
    }

    public SiteState getState() {
        return state;
    }

    public @Nullable Instant getOpensAt() {
        return opensAt;
    }

    public @Nullable Instant getEndsAt() {
        return endsAt;
    }

    public PassFloor getPassFloor() {
        return passFloor;
    }

    public boolean isStaffLoginVisible() {
        return staffLoginVisible;
    }

    public boolean isSignupsWhileClosed() {
        return signupsWhileClosed;
    }

    public long getRevision() {
        return revision;
    }

    public @Nullable Instant getUpdatedAt() {
        return updatedAt;
    }
}
