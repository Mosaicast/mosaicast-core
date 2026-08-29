// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.http;

import dev.mosaicast.core.external.error.ExternalProviderException;
import dev.mosaicast.core.external.error.ExternalTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one HTTP client external-service providers speak through (ARCHITECTURE §12.7).
 *
 * <p>Built like {@code RssFeedSource}'s and tightened twice, because a translation API is a narrower thing
 * than an arbitrary podcast feed:
 *
 * <ul>
 *   <li><strong>A 3xx is an error, not a hop.</strong> Feeds re-validate each redirect against the target
 *       policy; a translation endpoint has no business redirecting at all, and refusing outright is both
 *       simpler and strictly safer.</li>
 *   <li><strong>2 MB body cap</strong> rather than 16 MB. A translation response is bounded by its request;
 *       anything larger is a misdirected URL or a hostile one.</li>
 * </ul>
 *
 * <p>The timeout reasoning is inherited verbatim: {@code HttpRequest.timeout} bounds the wait for a
 * <em>response</em>, which a host that answers promptly and then dribbles one byte per second satisfies
 * forever. Only a wall-clock budget over the complete exchange closes that, so the send is async and joined
 * against a deadline.
 */
@Component
public class ExternalHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalHttpClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** The most response body that will be read. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final String USER_AGENT = "Mosaicast/1.0 (+https://github.com/mosaicast)";

    private final HttpClient http;
    private final ExternalTargetPolicy targets;

    public ExternalHttpClient(ExternalTargetPolicy targets) {
        this.targets = targets;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * POSTs a JSON body and returns the response body as text.
     *
     * <p>The target is re-validated here rather than trusted from settings — DNS moves, and a hostname that
     * was public when the admin saved it need not be now. Same reason {@code RssFeedSource} re-checks.
     *
     * @param url      the absolute endpoint
     * @param json     the request body
     * @param headers  extra headers; <strong>credentials belong here</strong>, never in the query string,
     *                 where they would land in the upstream's access log
     * @param budget   wall-clock ceiling for the whole exchange
     * @return the response body
     * @throws ExternalTimeoutException  if the exchange outlives its budget
     * @throws ExternalProviderException on a transport failure, a redirect, an oversized body, or a non-2xx
     *                                   status. <strong>The message never carries the upstream body</strong>
     */
    public String postJson(String url, String json, Map<String, String> headers, Duration budget) {
        URI uri = targets.validate(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(budget)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        return send(request.build(), budget, uri);
    }

    /** GETs a URL and returns the response body as text. Same rules as {@link #postJson}. */
    public String get(String url, Map<String, String> headers, Duration budget) {
        URI uri = targets.validate(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(budget)
                .GET();
        headers.forEach(request::header);
        return send(request.build(), budget, uri);
    }

    private String send(HttpRequest request, Duration budget, URI uri) {
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(budget.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            pending.cancel(true);
            throw new ExternalTimeoutException(
                    "The service did not answer within %s seconds".formatted(budget.toSeconds()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new ExternalProviderException("The call was interrupted");
        } catch (ExecutionException failed) {
            // The cause names the host and sometimes the internal error; log it, do not return it.
            log.warn("External service call to {} failed", uri.getHost(), failed.getCause());
            throw new ExternalProviderException("The service could not be reached");
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            log.warn("External service at {} answered {} — redirects are not followed", uri.getHost(), status);
            throw new ExternalProviderException("The service redirected, which is not supported");
        }
        byte[] body = response.body();
        if (body != null && body.length > MAX_BODY_BYTES) {
            throw new ExternalProviderException("The service returned more data than expected");
        }
        if (status < 200 || status >= 300) {
            // Deliberately not the upstream body: it can echo the request, credential included, and for a
            // private endpoint it is a read-back oracle for whatever is actually listening there.
            log.warn("External service at {} answered HTTP {}", uri.getHost(), status);
            throw new ExternalProviderException("The service answered with an error (HTTP %d)".formatted(status));
        }
        return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
    }
}
