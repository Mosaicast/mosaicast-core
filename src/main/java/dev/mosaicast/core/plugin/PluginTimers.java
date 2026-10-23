// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Scheduled;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code ctx.runAfter} (platformApi 0.20.0, ARCHITECTURE §7.9): one-shot tasks below the {@code onSchedule}
 * floor — a turn clock, a 30-second vote.
 *
 * <p>In memory and on this instance only: not ShedLock'd, not persisted, so a restart drops every pending
 * task and a plugin re-arms them in {@code register} from what it stored. The clock thread here only decides
 * <em>when</em>; the task itself runs in the plugin's own budget ({@link PluginRealtimeExecutor}), so a slow
 * timer holds that plugin's slots and nobody else's. A plugin switched off has its pending tasks cancelled,
 * and one that fires in the instant before that is skipped.
 */
@Component
public class PluginTimers {

    private static final int PENDING = 0;
    private static final int STARTED = 1;
    private static final int CANCELLED = 2;

    private final PluginRealtimeProperties properties;
    private final PluginRealtimeExecutor executor;
    private final PluginSettingsService settings;
    private final ScheduledThreadPoolExecutor clock;
    private final ConcurrentHashMap<String, Set<Handle>> pending = new ConcurrentHashMap<>();

    public PluginTimers(PluginRealtimeProperties properties, PluginRealtimeExecutor executor,
                        PluginSettingsService settings) {
        this.properties = properties;
        this.executor = executor;
        this.settings = settings;
        this.clock = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("plugin-timer-", 0).daemon().factory());
        // A cancelled turn clock should not sit in the queue until its original deadline.
        this.clock.setRemoveOnCancelPolicy(true);
    }

    /**
     * Schedules one run of {@code task} after {@code delay}.
     *
     * @throws IllegalArgumentException a null or negative delay, or a null task — never clamped
     * @throws IllegalStateException    the plugin already has the operator's maximum of pending tasks
     */
    Scheduled schedule(String pluginId, Duration delay, Runnable task) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("runAfter needs a delay of zero or more, not " + delay);
        }
        Objects.requireNonNull(task, "task");
        Set<Handle> mine = pending.computeIfAbsent(pluginId, id -> ConcurrentHashMap.newKeySet());
        if (mine.size() >= properties.maxPendingTimers()) {
            throw new IllegalStateException("plugin '%s' already has %d pending runAfter tasks"
                    .formatted(pluginId, properties.maxPendingTimers()));
        }
        Handle handle = new Handle(pluginId, task, mine);
        mine.add(handle);
        handle.future = clock.schedule(handle::fire, delay.toNanos(), TimeUnit.NANOSECONDS);
        return handle;
    }

    /** Cancels every pending task of one plugin — on switch-off, or when its {@code register} failed. */
    void cancelAll(String pluginId) {
        Set<Handle> mine = pending.get(pluginId);
        if (mine != null) {
            for (Handle handle : Set.copyOf(mine)) {
                handle.cancel();
            }
        }
    }

    /** How many tasks a plugin has waiting; for tests and the admin view. */
    int pendingCount(String pluginId) {
        Set<Handle> mine = pending.get(pluginId);
        return mine == null ? 0 : mine.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onDisabled(PluginDisabledEvent event) {
        cancelAll(event.pluginId());
    }

    @PreDestroy
    void shutdown() {
        clock.shutdownNow();
    }

    private final class Handle implements Scheduled {

        private final String pluginId;
        private final Runnable task;
        private final Set<Handle> owner;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private volatile ScheduledFuture<?> future;

        Handle(String pluginId, Runnable task, Set<Handle> owner) {
            this.pluginId = pluginId;
            this.task = task;
            this.owner = owner;
        }

        /** False when the task already ran or was cancelled — the one answer a turn clock needs. */
        @Override
        public boolean cancel() {
            if (!state.compareAndSet(PENDING, CANCELLED)) {
                return false;
            }
            owner.remove(this);
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
            return true;
        }

        void fire() {
            if (!state.compareAndSet(PENDING, STARTED)) {
                return;
            }
            owner.remove(this);
            if (settings.enabled(pluginId)) {
                executor.submit(pluginId, "runAfter task", task);
            }
        }
    }
}
