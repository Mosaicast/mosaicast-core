// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodePhaseChangedEvent;
import dev.mosaicast.core.episode.EpisodeReleasedEvent;
import dev.mosaicast.plugin.api.EpisodePhase;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a release to every active plugin that listens for one ({@code PluginContext.onEpisodeReleased},
 * SDK 0.18.0, core#252), and any other phase change a write made ({@code onEpisodePhaseChanged}, SDK 0.19.0,
 * core#270).
 *
 * <p><strong>A release is both.</strong> An {@link EpisodeReleasedEvent} reaches a plugin's release listeners
 * and then its phase listeners with {@code RELEASED} — in that order, as the SDK documents and its test kit
 * does, so the release paths publish one event rather than two that could arrive either way round. That order
 * is why each plugin's listeners run one after another on <em>one</em> virtual thread of their own: a plugin
 * taking its time delays only itself, and a throwing listener is logged and the next one still runs.
 *
 * <p><strong>After the commit</strong>, so a plugin that reads the episode in its handler sees it released —
 * and a binding that rolls back announces nothing. <strong>Off the caller's thread</strong>, one virtual thread
 * per listener: the release is announced from inside a feed poll or an admin request, and a plugin taking its
 * time must not hold either. <strong>Best effort</strong>, as the SDK documents: nothing is queued or retried,
 * and a plugin that was down misses the event — it reconciles by phase on its schedule.
 */
@Component
public class EpisodeReleaseDispatcher {

    private final PluginLoaderService plugins;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EpisodeReleaseDispatcher(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRelease(EpisodeReleasedEvent event) {
        plugins.activeContexts().forEach(context -> {
            if (context.releaseListeners().isEmpty() && context.phaseListeners().isEmpty()) {
                return;
            }
            executor.execute(() -> {
                context.releaseListeners().forEach(listener -> {
                    try {
                        listener.accept(event.slug());
                    } catch (RuntimeException e) {
                        // Into that plugin's own log, where its operator looks — not the host's.
                        context.logger().warn("onEpisodeReleased handler failed for '{}'", event.slug(), e);
                    }
                });
                tellPhase(context, event.slug(), EpisodePhase.RELEASED);
            });
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPhaseChanged(EpisodePhaseChangedEvent event) {
        plugins.activeContexts().forEach(context -> {
            if (!context.phaseListeners().isEmpty()) {
                executor.execute(() -> tellPhase(context, event.slug(), event.phase()));
            }
        });
    }

    private static void tellPhase(PluginContextImpl context, String slug, EpisodePhase phase) {
        context.phaseListeners().forEach(listener -> {
            try {
                listener.accept(slug, phase);
            } catch (RuntimeException e) {
                context.logger().warn("onEpisodePhaseChanged handler failed for '{}' ({})", slug, phase, e);
            }
        });
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
