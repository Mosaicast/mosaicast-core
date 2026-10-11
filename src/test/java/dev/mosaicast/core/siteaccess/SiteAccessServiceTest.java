// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.web.CodedBadRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The policy's rules on a pinned clock (ARCHITECTURE §18.1, §18.3): when a scheduled site counts as open,
 * which times are refused, what "open now" means, and when a first login may create an account.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:59:00Z");

    @Mock
    private SiteAccessRepository repository;

    private SiteAccessService service;

    @BeforeEach
    void setUp() {
        service = new SiteAccessService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findById(SiteAccess.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(SiteAccess.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void noSavedPolicyMeansOpenAndSignupsAllowed() {
        assertThat(service.current().getState()).isEqualTo(SiteState.OPEN);
        assertThat(service.allowsNewAccounts()).isTrue();
    }

    @Test
    void aScheduledSiteIsOpenFromItsMomentOn() {
        SiteAccess access = service.update(change(SiteState.SCHEDULED, NOW.plusSeconds(3600), null, false), null);

        assertThat(access.effectiveState(NOW)).isEqualTo(SiteState.SCHEDULED);
        assertThat(access.effectiveState(NOW.plusSeconds(3599))).isEqualTo(SiteState.SCHEDULED);
        assertThat(access.effectiveState(NOW.plusSeconds(3600))).isEqualTo(SiteState.OPEN);
    }

    @Test
    void anOpeningLessThanAMinuteAwayIsRefused() {
        assertThatThrownBy(() -> service.update(change(SiteState.SCHEDULED, NOW.plusSeconds(59), null, false), null))
                .isInstanceOf(CodedBadRequest.class)
                .extracting(e -> ((CodedBadRequest) e).code())
                .isEqualTo("siteAccess.opensAt.tooSoon");
        assertThatThrownBy(() -> service.update(change(SiteState.SCHEDULED, null, null, false), null))
                .extracting(e -> ((CodedBadRequest) e).code())
                .isEqualTo("siteAccess.opensAt.missing");
    }

    @Test
    void maintenanceTakesOnlyAFutureEndAndOtherStatesDropTimes() {
        assertThatThrownBy(() -> service.update(change(SiteState.MAINTENANCE, null, NOW.minusSeconds(1), false), null))
                .extracting(e -> ((CodedBadRequest) e).code())
                .isEqualTo("siteAccess.endsAt.past");

        SiteAccess closed = service.update(change(SiteState.CLOSED, NOW.plusSeconds(3600), NOW.plusSeconds(60), false), null);
        assertThat(closed.getOpensAt()).isNull();
        assertThat(closed.getEndsAt()).isNull();
    }

    @Test
    void openNowSchedulesTwelveSecondsOutAndKeepsTheOtherSettings() {
        SiteAccess current = service.update(
                new SiteAccessService.Change(SiteState.CLOSED, null, null, PassFloor.ADMIN, true, false), null);
        when(repository.findById(SiteAccess.SINGLETON_ID)).thenReturn(Optional.of(current));

        SiteAccess opened = service.openNow(null);

        assertThat(opened.getState()).isEqualTo(SiteState.SCHEDULED);
        assertThat(opened.getOpensAt()).isEqualTo(NOW.plusSeconds(12));
        assertThat(opened.getPassFloor()).isEqualTo(PassFloor.ADMIN);
        assertThat(opened.isStaffLoginVisible()).isTrue();
        assertThat(opened.getRevision()).isEqualTo(2);
    }

    @Test
    void signupsAreRefusedWhileNotOpenUnlessSwitchedOn() {
        SiteAccess closed = service.update(change(SiteState.CLOSED, null, null, false), null);
        when(repository.findById(SiteAccess.SINGLETON_ID)).thenReturn(Optional.of(closed));
        assertThat(service.allowsNewAccounts()).isFalse();

        SiteAccess allowing = service.update(change(SiteState.CLOSED, null, null, true), null);
        when(repository.findById(SiteAccess.SINGLETON_ID)).thenReturn(Optional.of(allowing));
        assertThat(service.allowsNewAccounts()).isTrue();
    }

    @Test
    void aPassFloorOfAdminStillAdmitsAdminsAndPodcasterAdmitsBoth() {
        assertThat(PassFloor.ADMIN.admits(dev.mosaicast.plugin.api.Role.ADMIN)).isTrue();
        assertThat(PassFloor.ADMIN.admits(dev.mosaicast.plugin.api.Role.PODCASTER)).isFalse();
        assertThat(PassFloor.PODCASTER.admits(dev.mosaicast.plugin.api.Role.PODCASTER)).isTrue();
        assertThat(PassFloor.PODCASTER.admits(dev.mosaicast.plugin.api.Role.FAN)).isFalse();
    }

    private static SiteAccessService.Change change(SiteState state, Instant opensAt, Instant endsAt,
                                                   boolean signupsWhileClosed) {
        return new SiteAccessService.Change(state, opensAt, endsAt, PassFloor.PODCASTER, false, signupsWhileClosed);
    }
}
