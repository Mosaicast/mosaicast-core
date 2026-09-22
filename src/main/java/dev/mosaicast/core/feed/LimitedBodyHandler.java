// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link HttpResponse.BodyHandler} that reads at most a fixed number of bytes and aborts the exchange if the
 * remote tries to send more.
 *
 * <p>{@code BodyHandlers.ofByteArray()} has no ceiling: it buffers whatever arrives into a single array and
 * hands it on. For a feed body that is fetched from an operator-supplied URL and then parsed into a full JDOM
 * tree — several times the byte size again in heap — that is an out-of-memory condition one text field away.
 * A response that never finishes is the same problem in slow motion.
 *
 * <p>Two gates, because either alone is bypassable. A declared {@code Content-Length} over the cap is refused
 * before a single body byte is read, which costs the sender nothing to omit; so the bytes are also counted as
 * they arrive and the subscription is cancelled the moment the count is exceeded. Chunked encoding, a lying
 * {@code Content-Length} and a body that simply never ends are all the second case.
 */
public final class LimitedBodyHandler {

    private LimitedBodyHandler() {
    }

    public static HttpResponse.BodyHandler<byte[]> of(long maxBytes) {
        return info -> {
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) {
                return new FailingSubscriber(new BodyTooLargeException(
                        "Content-Length " + declared + " exceeds the " + maxBytes + " byte limit"));
            }
            return new LimitedSubscriber(HttpResponse.BodySubscribers.ofByteArray(), maxBytes);
        };
    }

    /**
     * Counts bytes through to a delegate and cancels the subscription once the cap is passed.
     *
     * <p>Cancelling matters as much as the count does: it tears the connection down rather than politely
     * reading a multi-gigabyte body to completion and discarding it at the end.
     */
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> delegate;
        private final long maxBytes;
        private final AtomicLong seen = new AtomicLong();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private volatile boolean aborted;

        LimitedSubscriber(HttpResponse.BodySubscriber<byte[]> delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            delegate.getBody().whenComplete((body, error) -> {
                if (aborted) {
                    return;
                }
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(body);
                }
            });
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            long total = seen.addAndGet(buffers.stream().mapToLong(ByteBuffer::remaining).sum());
            if (total > maxBytes) {
                abort(new BodyTooLargeException("Body exceeds the " + maxBytes + " byte limit"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!aborted) {
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!aborted) {
                delegate.onComplete();
            }
        }

        private void abort(Throwable reason) {
            aborted = true;
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            result.completeExceptionally(reason);
        }
    }

    /** Rejects the body without reading it — used when {@code Content-Length} already exceeds the cap. */
    private static final class FailingSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final Throwable reason;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        FailingSubscriber(Throwable reason) {
            this.reason = reason;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            result.completeExceptionally(reason);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // Cancelled at subscribe; nothing should arrive, and anything that does is discarded.
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(reason);
        }

        @Override
        public void onComplete() {
            result.completeExceptionally(reason);
        }
    }
}
