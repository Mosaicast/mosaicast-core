// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The scheduler's half of {@code onSchedule} (ARCHITECTURE §7.4).
 *
 * <p>What matters here is what happens <em>around</em> a tick rather than the clock: the period is re-read
 * before every fire, a plugin that changes it is followed within one period, and a supplier that starts
 * failing keeps the schedule it had. Before 0.15.0 the period was captured during {@code register()}, so an
 * operator's edit to a plugin's interval was accepted, stored, reported as saved — and silently ignored
 * until the host restarted.
 */
class PluginSchedulerTest {

    private static final String LOCK = "plugin:acme:0";

    private PluginSettingsService settings;
    private PluginScheduler scheduler;

    @BeforeEach
    void setUp() {
        LockProvider locks = mock(LockProvider.class);
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        settings = mock(PluginSettingsService.class);
        when(settings.enabled("acme")).thenReturn(true);
        // A stub TaskScheduler, so every run in these tests is one this test asked for: a real pool fires a
        // fixed-rate task immediately, on another thread, and turns every count into a race.
        scheduler = new PluginScheduler(locks, settings, new PluginScheduleProperties(Duration.ofSeconds(1)),
                mock(TaskScheduler.class));
    }

    @Test
    void aChangedPeriodIsPickedUpAtTheNextTick() {
        AtomicReference<Duration> period = new AtomicReference<>(Duration.ofSeconds(60));
        AtomicInteger runs = new AtomicInteger();
        scheduler.schedule("acme", 0, period::get, runs::incrementAndGet);

        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(60));

        // What an operator saving `ingestIntervalSeconds = 10` looks like from here. Counted as a delta:
        // `scheduleAtFixedRate` fires once immediately on registration, so absolute counts measure the
        // scheduler's own first tick as much as ours.
        int before = runs.get();
        period.set(Duration.ofSeconds(10));
        scheduler.tick(LOCK);

        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(10));
        // The tick still ran: a reschedule is not a skipped turn.
        assertThat(runs.get() - before).isEqualTo(1);
    }

    @Test
    void anUnchangedPeriodJustRuns() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.schedule("acme", 0, () -> Duration.ofSeconds(30), runs::incrementAndGet);

        int before = runs.get();
        scheduler.tick(LOCK);
        scheduler.tick(LOCK);

        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(30));
        assertThat(runs.get() - before).isEqualTo(2);
    }

    @Test
    void aSupplierThatBreaksKeepsTheLastPeriodThatWorked() {
        AtomicReference<Duration> period = new AtomicReference<>(Duration.ofSeconds(45));
        AtomicInteger runs = new AtomicInteger();
        scheduler.schedule("acme", 0, () -> {
            Duration current = period.get();
            if (current == null) {
                throw new IllegalStateException("config read failed");
            }
            return current;
        }, runs::incrementAndGet);

        int before = runs.get();
        period.set(null);
        scheduler.tick(LOCK);

        // Losing a schedule because a config read started failing would be worse than the failure itself.
        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(45));
        assertThat(runs.get() - before).isEqualTo(1);
    }

    @Test
    void aNonPositiveAnswerIsIgnoredAsWell() {
        AtomicReference<Duration> period = new AtomicReference<>(Duration.ofSeconds(45));
        scheduler.schedule("acme", 0, period::get, () -> { });

        period.set(Duration.ZERO);
        scheduler.tick(LOCK);
        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(45));

        period.set(Duration.ofSeconds(-5));
        scheduler.tick(LOCK);
        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void registrationIsStrict() {
        // There is no last-valid period to fall back to yet, and a plugin that cannot name one at
        // registration has a bug its author can see.
        assertThatThrownBy(() -> scheduler.schedule("acme", 0, () -> Duration.ZERO, () -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> scheduler.schedule("acme", 0, () -> null, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAskBelowTheOperatorsFloorIsClamped() {
        PluginScheduler floored = new PluginScheduler(lockProvider(), settings,
                new PluginScheduleProperties(Duration.ofSeconds(10)), mock(TaskScheduler.class));
        AtomicReference<Duration> period = new AtomicReference<>(Duration.ofSeconds(1));
        floored.schedule("acme", 0, period::get, () -> { });

        // The plugin's number is a request, like the manifest's other numbers.
        assertThat(floored.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(10));

        period.set(Duration.ofSeconds(30));
        floored.tick(LOCK);
        assertThat(floored.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aSwitchedOffPluginStopsRunningButKeepsItsSchedule() {
        when(settings.enabled("acme")).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();
        scheduler.schedule("acme", 0, () -> Duration.ofSeconds(30), runs::incrementAndGet);

        scheduler.tick(LOCK);

        // Not once, including the registration tick: switching a plugin off quiets it immediately.
        assertThat(runs.get()).isZero();
        assertThat(scheduler.periodOf(LOCK)).isEqualTo(Duration.ofSeconds(30));
    }

    private LockProvider lockProvider() {
        LockProvider locks = mock(LockProvider.class);
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        return locks;
    }
}
