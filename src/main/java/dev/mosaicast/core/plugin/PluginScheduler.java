// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs plugin {@code onSchedule} tasks (ARCHITECTURE §7.4). Each tick is wrapped in ShedLock so it runs at
 * most once across instances (reusing the feed scheduler's {@link LockProvider}), and in a try/catch so a
 * throwing plugin task can never take the scheduler — or the site — down (§7.8), mirroring
 * {@code FeedScheduler}'s per-item isolation.
 */
@Component
public class PluginScheduler {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final LockingTaskExecutor lockingExecutor;

    public PluginScheduler(LockProvider lockProvider) {
        this.lockingExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(2);
        this.taskScheduler.setThreadNamePrefix("plugin-sched-");
        this.taskScheduler.initialize();
    }

    /**
     * Schedules a plugin task at a fixed rate under a unique ShedLock name.
     *
     * @param pluginId the owning plugin
     * @param index    the 0-based index of this task within the plugin (for a stable lock name)
     * @param every    the period; must be positive
     * @param task     the work to run each tick
     * @throws IllegalArgumentException if {@code every} is zero or negative
     */
    public void schedule(String pluginId, int index, Duration every, Runnable task) {
        if (every == null || every.isZero() || every.isNegative()) {
            throw new IllegalArgumentException("onSchedule period must be positive, was " + every);
        }
        String lockName = "plugin:" + pluginId + ":" + index;
        taskScheduler.scheduleAtFixedRate(() -> runLocked(lockName, every, task), every);
    }

    private void runLocked(String lockName, Duration every, Runnable task) {
        LockConfiguration lock = new LockConfiguration(Instant.now(), lockName, every, Duration.ZERO);
        lockingExecutor.executeWithLock((Runnable) () -> {
            try {
                task.run();
            } catch (Exception e) {
                // Isolated: one misbehaving plugin task must never starve the scheduler or crash the host.
                log.warn("Plugin scheduled task {} failed", lockName, e);
            }
        }, lock);
    }
}
