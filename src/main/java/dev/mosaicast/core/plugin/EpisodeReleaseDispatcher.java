// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeReleasedEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a release to every active plugin that listens for one ({@code PluginContext.onEpisodeReleased},
 * SDK 0.18.0, core#252).
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
        plugins.activeContexts().forEach(context -> context.releaseListeners().forEach(listener ->
                executor.execute(() -> {
                    try {
                        listener.accept(event.slug());
                    } catch (RuntimeException e) {
                        // Into that plugin's own log, where its operator looks — not the host's.
                        context.logger().warn("onEpisodeReleased handler failed for '{}'", event.slug(), e);
                    }
                })));
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
