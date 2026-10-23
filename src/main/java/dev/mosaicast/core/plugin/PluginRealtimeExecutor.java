// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.log.AppLogAppender;
import dev.mosaicast.core.web.PluginCallRefusedException;
import dev.mosaicast.plugin.api.ActionRejectedException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Where plugin code runs at request time and on realtime events (platformApi 0.20.0, ARCHITECTURE §7.9):
 * action handlers, channel authorizers, join/leave listeners and {@code runAfter} timers.
 *
 * <p><strong>A bounded budget per plugin.</strong> Every plugin gets {@code actionConcurrency} slots, shared
 * by all four kinds of call, and plugin code never runs on a servlet thread. Without that a handler stuck on
 * a lock would hold request threads until the pages every other visitor needs stopped being served; with it,
 * one plugin holds at most its own slots, and a caller that cannot get one is told {@code action-busy} after
 * a short wait instead of queueing behind it. A caller waits at most the deadline for an answer — the code
 * keeps running past it (Java cannot stop a thread, only ask), but the caller is answered and the slot is
 * freed only when that code really ends, so a plugin that keeps timing out ends up busy rather than
 * unbounded.
 *
 * <p>Failures are the plugin's, so they go to <strong>its</strong> log ({@code plugin.<id>}), as a release
 * listener's do; the caller gets the generic 500 and nothing of the plugin's message.
 */
@Component
public class PluginRealtimeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginRealtimeExecutor.class);

    private final PluginRealtimeProperties properties;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Semaphore> slots = new ConcurrentHashMap<>();

    public PluginRealtimeExecutor(PluginRealtimeProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs plugin code for a caller who is waiting on the answer.
     *
     * <p>{@link ActionRejectedException} becomes the 422 the plugin meant; a payload the host could not read
     * ({@link UnreadablePayloadException}) stays the 400 it is; anything else is logged to the plugin's log
     * and becomes a 500.
     *
     * @throws PluginCallRefusedException busy, timeout, rejected or failed
     */
    public <T> T call(String pluginId, String what, Callable<T> task) {
        Semaphore budget = slotsOf(pluginId);
        try {
            if (!budget.tryAcquire(properties.actionQueueWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw PluginCallRefusedException.busy(pluginId, properties.actionQueueWait());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PluginCallRefusedException.busy(pluginId, properties.actionQueueWait());
        }
        Future<T> future;
        try {
            future = threads.submit(() -> {
                try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", pluginId)) {
                    return task.call();
                } finally {
                    budget.release();
                }
            });
        } catch (RuntimeException e) {
            budget.release();
            throw e;
        }
        try {
            return future.get(properties.actionDeadline().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            pluginLog(pluginId).warn("{} did not answer within {} ms; the caller was told it timed out",
                    what, properties.actionDeadline().toMillis());
            throw PluginCallRefusedException.timeout(pluginId, properties.actionDeadline());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw PluginCallRefusedException.timeout(pluginId, properties.actionDeadline());
        } catch (ExecutionException e) {
            throw translate(pluginId, what, e.getCause());
        }
    }

    /**
     * Runs plugin code nobody is waiting on — a timer, a join or leave listener. It waits for one of the
     * plugin's slots on its own virtual thread, so it can neither be refused nor take a slot the budget does
     * not have, and a failure is logged to the plugin's log and goes no further.
     */
    public void submit(String pluginId, String what, Runnable task) {
        Semaphore budget = slotsOf(pluginId);
        threads.execute(() -> {
            try (MDC.MDCCloseable ignored = MDC.putCloseable("pluginId", pluginId)) {
                budget.acquire();
                try {
                    task.run();
                } finally {
                    budget.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                pluginLog(pluginId).warn("{} failed", what, t);
            }
        });
    }

    private RuntimeException translate(String pluginId, String what, Throwable cause) {
        if (cause instanceof ActionRejectedException rejected) {
            return PluginCallRefusedException.rejected(rejected.code(), rejected.detail());
        }
        if (cause instanceof UnreadablePayloadException unreadable) {
            return unreadable;
        }
        if (cause instanceof PluginCallRefusedException refused) {
            return refused;
        }
        pluginLog(pluginId).warn("{} failed", what, cause);
        return PluginCallRefusedException.failed(pluginId);
    }

    private Semaphore slotsOf(String pluginId) {
        return slots.computeIfAbsent(pluginId, id -> new Semaphore(Math.max(1, properties.actionConcurrency())));
    }

    private static Logger pluginLog(String pluginId) {
        return LoggerFactory.getLogger(AppLogAppender.loggerNameFor(pluginId));
    }

    @PreDestroy
    void shutdown() {
        threads.shutdownNow();
        log.debug("Plugin realtime executor stopped");
    }
}
