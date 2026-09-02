// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.pipeline;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.error.ExternalBusyException;
import dev.mosaicast.core.external.error.ExternalServiceException;
import dev.mosaicast.core.external.error.ExternalTimeoutException;
import dev.mosaicast.core.external.settings.ProviderConfig;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bulkhead: how many calls to one kind may be in flight at once (ARCHITECTURE §12.7).
 *
 * <p>Answers the "a hundred users press translate at the same time" problem. Without it, a hundred servlet
 * threads sit blocked on somebody else's server and the site stops serving pages that have nothing to do
 * with translation.
 *
 * <p><strong>One semaphore per kind, not per provider.</strong> Only one provider is active for a kind at a
 * time, so per-kind is the honest bound and it survives a provider switch without leaking permits.
 *
 * <p><strong>The semaphore is the bound; virtual threads are not.</strong> Threads are cheap — sockets, and
 * the remote service's patience, are not. Following {@code PluginExtensions}, work runs on a
 * virtual-thread-per-task executor and the permit count is what actually limits concurrency.
 *
 * <p>Waiting for a permit and waiting for a remote host are reported differently on purpose:
 * {@link ExternalBusyException} is 503 "we are full", {@link ExternalTimeoutException} is 504 "they did not
 * answer". They have different fixes, and one status covering both would tell an operator nothing.
 */
public final class BoundedCallProvider<I, O> implements ExternalProvider<I, O> {

    private final ExternalProvider<I, O> delegate;
    private final Semaphore permits;
    private final ExecutorService executor;
    private final Duration queueWait;
    private final Duration callBudget;

    public BoundedCallProvider(ExternalProvider<I, O> delegate, Semaphore permits, ExecutorService executor,
                               Duration queueWait, Duration callBudget) {
        this.delegate = delegate;
        this.permits = permits;
        this.executor = executor;
        this.queueWait = queueWait;
        this.callBudget = callBudget;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public O call(I input, ProviderConfig config) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalBusyException("The call was interrupted while waiting to start");
        }
        if (!acquired) {
            // The caller here is a servlet thread, so the wait to even begin is kept short on purpose.
            throw new ExternalBusyException(
                    "Too many %s calls are already running; try again shortly".formatted(
                            descriptor().kind().id()));
        }
        try {
            return run(() -> delegate.call(input, config));
        } finally {
            permits.release();
        }
    }

    @Override
    public ProbeResult probe(ProviderConfig config) {
        // Probes take a permit too: an admin hammering Test must not be able to starve real work.
        if (!permits.tryAcquire()) {
            return ProbeResult.failed("Too many calls are already running; try again shortly");
        }
        try {
            return run(() -> delegate.probe(config));
        } finally {
            permits.release();
        }
    }

    /**
     * Runs the delegate under a wall-clock ceiling.
     *
     * <p>The HTTP client already budgets its own exchange; this bounds everything else a provider might do
     * — parsing, a second request, whatever a future provider adds — so one misbehaving implementation
     * cannot hold a permit indefinitely and starve the kind.
     */
    private <T> T run(java.util.concurrent.Callable<T> work) {
        Future<T> pending = executor.submit(work);
        try {
            return pending.get(callBudget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new ExternalTimeoutException("The %s call did not finish within %d seconds"
                    .formatted(descriptor().kind().id(), callBudget.toSeconds()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new ExternalBusyException("The call was interrupted");
        } catch (ExecutionException failed) {
            // A provider's own refusal must arrive as itself, with its own status and problem type, rather
            // than wrapped into something generic by the machinery that happened to be running it.
            if (failed.getCause() instanceof ExternalServiceException known) {
                throw known;
            }
            if (failed.getCause() instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            throw new IllegalStateException(failed.getCause());
        }
    }

    /**
     * Delegated, not inherited.
     *
     * <p>{@link ExternalProvider}'s defaults are "one unit per call" and "the estimate"; a kind overrides
     * them with what it actually costs — a translation counts code points. A decorator that let the default
     * stand would report a 5,000-character call as one unit, and it is the decorator that every caller
     * reaches, so the override would never be consulted by anyone. Silent, and in the direction of
     * understating a bill.
     */
    @Override
    public long estimateUnits(I input) {
        return delegate.estimateUnits(input);
    }

    @Override
    public long actualUnits(I input, O output) {
        return delegate.actualUnits(input, output);
    }

}
