// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs plugin {@code onSchedule} tasks (ARCHITECTURE §7.4). Each tick is wrapped in ShedLock so it runs at
 * most once across instances (reusing the feed scheduler's {@link LockProvider}), and in a try/catch so a
 * throwing plugin task can never take the scheduler — or the site — down (§7.8), mirroring
 * {@code FeedScheduler}'s per-item isolation.
 *
 * <p><strong>The period is re-read, not captured</strong> ({@code platformApi} 0.15.0). A plugin hands over
 * a {@link Supplier}, the scheduler consults it before every fire, and reschedules when the answer changes.
 * Before that, the period was fixed during {@code register()}: a plugin whose tick rate came from config
 * accepted a new value, stored it, reported success in the admin form, and went on running at the old
 * cadence until the host restarted. A change now takes effect within one old period.
 *
 * <p>The supplier is consulted, not trusted. It runs on a scheduler thread, so a plugin that throws, returns
 * {@code null}, or returns a non-positive period keeps running at the last period that was valid — losing a
 * schedule because a config read started failing would be a worse failure than the one it reports. Only
 * registration is strict, because there is no last-valid period to fall back to yet.
 */
@Component
public class PluginScheduler {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);

    private final TaskScheduler taskScheduler;
    private final LockingTaskExecutor lockingExecutor;
    private final PluginSettingsService settings;
    private final PluginScheduleProperties properties;

    /** The live tasks, by lock name. Entries are added at registration and never removed while the host runs. */
    private final Map<String, ScheduledPluginTask> tasks = new ConcurrentHashMap<>();

    // Explicit, because the package-private constructor below makes this an ambiguous choice for the
    // container: with two candidates Spring looks for the annotation rather than guessing.
    @Autowired
    public PluginScheduler(LockProvider lockProvider, PluginSettingsService settings,
                           PluginScheduleProperties properties) {
        this(lockProvider, settings, properties, defaultTaskScheduler());
    }

    /**
     * The same scheduler against a supplied {@link TaskScheduler}.
     *
     * <p>Package-private, for the test: a real pool fires a fixed-rate task immediately and on its own
     * thread, which makes "did this tick run once?" a race rather than an assertion.
     */
    PluginScheduler(LockProvider lockProvider, PluginSettingsService settings,
                    PluginScheduleProperties properties, TaskScheduler taskScheduler) {
        this.settings = settings;
        this.properties = properties;
        this.lockingExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.taskScheduler = taskScheduler;
    }

    private static TaskScheduler defaultTaskScheduler() {
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(2);
        pool.setThreadNamePrefix("plugin-sched-");
        pool.initialize();
        return pool;
    }

    /**
     * Schedules a plugin task under a unique ShedLock name, at a period the host re-reads before each tick.
     *
     * @param pluginId the owning plugin
     * @param index    the 0-based index of this task within the plugin (for a stable lock name)
     * @param every    supplies the period; consulted here and again before every fire
     * @param task     the work to run each tick
     * @throws IllegalArgumentException if the period supplied at registration is not positive
     */
    public void schedule(String pluginId, int index, Supplier<Duration> every, Runnable task) {
        Objects.requireNonNull(every, "every");
        Objects.requireNonNull(task, "task");
        // Strict here and nowhere else: a plugin that cannot name a valid period at registration has a bug
        // its author can see, while one whose config read fails at tick 4000 has an operator problem that
        // must not cost it its schedule.
        Duration requested = every.get();
        if (requested == null || requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("onSchedule period must be positive, was " + requested);
        }
        String lockName = "plugin:" + pluginId + ":" + index;
        ScheduledPluginTask entry = new ScheduledPluginTask(pluginId, lockName, every, task);
        entry.period = clamp(requested, pluginId, lockName);
        tasks.put(lockName, entry);
        start(entry);
    }

    /** The period a task is currently running at — the scheduler's own view, for tests and diagnostics. */
    Duration periodOf(String lockName) {
        ScheduledPluginTask entry = tasks.get(lockName);
        return entry == null ? null : entry.period;
    }

    /**
     * Runs one tick of a registered task, exactly as the scheduler thread would.
     *
     * <p>Package-private for the test: the behaviour worth asserting — that a changed period is picked up,
     * and that a broken supplier does not cost a task its schedule — is about what happens around a fire,
     * not about the clock. Driving the tick directly keeps that test deterministic and instant instead of
     * making it sleep through real periods.
     */
    void tick(String lockName) {
        ScheduledPluginTask entry = tasks.get(lockName);
        if (entry != null) {
            fire(entry);
        }
    }

    private void start(ScheduledPluginTask entry) {
        entry.future = taskScheduler.scheduleAtFixedRate(() -> fire(entry), entry.period);
    }

    /**
     * One tick: re-read the period, run the task, and reschedule if the answer changed.
     *
     * <p>Run first, reschedule after. The alternative — swapping the schedule before doing the work — means
     * a plugin that shortens its period does its next tick twice in quick succession, once from the future
     * being cancelled and once from the new one.
     */
    private void fire(ScheduledPluginTask entry) {
        Duration next = nextPeriod(entry);
        runLocked(entry);
        if (!next.equals(entry.period)) {
            reschedule(entry, next);
        }
    }

    /**
     * What the supplier says now, clamped — or the period in force when it says nothing usable.
     *
     * <p>A bad answer is logged once per transition rather than on every tick: a plugin whose config read
     * fails will fail again in a second, and a log line per tick is how a warning becomes noise nobody reads.
     */
    private Duration nextPeriod(ScheduledPluginTask entry) {
        Duration answer;
        try {
            answer = entry.every.get();
        } catch (Exception e) {
            if (entry.reportedBadSupplier.compareAndSet(false, true)) {
                log.warn("Plugin scheduled task {} could not read its period; staying at {}",
                        entry.lockName, entry.period, e);
            }
            return entry.period;
        }
        if (answer == null || answer.isZero() || answer.isNegative()) {
            if (entry.reportedBadSupplier.compareAndSet(false, true)) {
                log.warn("Plugin scheduled task {} asked for a period of {}; staying at {}",
                        entry.lockName, answer, entry.period);
            }
            return entry.period;
        }
        entry.reportedBadSupplier.set(false);
        return clamp(answer, entry.pluginId, entry.lockName);
    }

    private void reschedule(ScheduledPluginTask entry, Duration next) {
        synchronized (entry) {
            if (entry.future != null) {
                // Not an interrupt: this runs on the scheduler thread from inside the task it is cancelling,
                // and the tick that is finishing must be allowed to finish.
                entry.future.cancel(false);
            }
            Duration previous = entry.period;
            entry.period = next;
            start(entry);
            log.info("Plugin scheduled task {} now runs every {} (was {})", entry.lockName, next, previous);
        }
    }

    /**
     * Applies the operator's floor. The plugin's number is a request, like the manifest's other numbers —
     * a plugin asking for a tick a second would otherwise put a ShedLock round-trip per second on every
     * instance, on the say-so of a value an operator typed into a form.
     */
    private Duration clamp(Duration requested, String pluginId, String lockName) {
        Duration floor = properties.minPeriodOrDefault();
        if (requested.compareTo(floor) >= 0) {
            return requested;
        }
        log.info("Plugin {} asked to run {} every {}; clamped to the {} floor "
                        + "(mosaicast.plugin-schedule.min-period)",
                pluginId, lockName, requested, floor);
        return floor;
    }

    private void runLocked(ScheduledPluginTask entry) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", entry.pluginId)) {
            runLockedTagged(entry);
        }
    }

    private void runLockedTagged(ScheduledPluginTask entry) {
        if (!settings.enabled(entry.pluginId)) {
            // Switched off while the host runs: the task stays registered but stops firing, so disabling a
            // plugin quiets it immediately instead of at the next restart (§7.8).
            return;
        }
        LockConfiguration lock = new LockConfiguration(Instant.now(), entry.lockName, entry.period, Duration.ZERO);
        lockingExecutor.executeWithLock((Runnable) () -> {
            try {
                entry.task.run();
            } catch (Exception e) {
                // Isolated: one misbehaving plugin task must never starve the scheduler or crash the host.
                log.warn("Plugin scheduled task {} failed", entry.lockName, e);
            }
        }, lock);
    }

    /** One registered task and the period it is currently running at. */
    private static final class ScheduledPluginTask {

        private final String pluginId;
        private final String lockName;
        private final Supplier<Duration> every;
        private final Runnable task;
        /** Whether the current run of bad answers has already been logged, so it is reported once. */
        private final java.util.concurrent.atomic.AtomicBoolean reportedBadSupplier =
                new java.util.concurrent.atomic.AtomicBoolean();

        private volatile Duration period;
        private volatile ScheduledFuture<?> future;

        private ScheduledPluginTask(String pluginId, String lockName, Supplier<Duration> every, Runnable task) {
            this.pluginId = pluginId;
            this.lockName = lockName;
            this.every = every;
            this.task = task;
        }
    }
}
