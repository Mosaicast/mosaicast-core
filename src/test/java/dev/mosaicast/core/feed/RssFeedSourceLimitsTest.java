// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound limits, exercised against a real HTTP server that misbehaves in the specific ways the
 * black-box audit exploited: an oversized body, a body that never ends, and a redirect into a private address.
 *
 * <p>The policy runs with the escape hatch open, because the fixture server is on loopback — these tests are
 * about the <em>other</em> guards. The redirect case constructs a restrictive policy on purpose, since that is
 * the one whose whole point is refusing loopback.
 */
class RssFeedSourceLimitsTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Small Cast</title>
              <item><title>An episode</title><guid>e1</guid></item>
            </channel></rss>
            """;

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/small.xml", exchange -> respond(exchange, FEED.getBytes(StandardCharsets.UTF_8)));

        // Declares an honest, enormous Content-Length. Refused before a body byte is read.
        server.createContext("/declared-huge", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, RssFeedSource.MAX_BODY_BYTES * 4);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(new byte[8192]);
            } catch (IOException expected) {
                // The client hangs up as soon as it reads the header — that is the point.
            }
        });

        // Lies about its length (chunked), then streams past the cap. Caught by the running byte count.
        server.createContext("/streamed-huge", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, 0); // chunked
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream os = exchange.getResponseBody()) {
                for (long sent = 0; sent < RssFeedSource.MAX_BODY_BYTES * 3; sent += chunk.length) {
                    os.write(chunk);
                }
            } catch (IOException expected) {
                // Connection cancelled once the cap was passed.
            }
        });

        // Answers promptly, then dribbles forever. Satisfies a response timeout; only a total budget stops it.
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 600; i++) {
                    os.write("<item>x</item>\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(500);
                }
            } catch (IOException | InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        // A redirect from this server to itself — stands in for "public host 302s into your private network".
        server.createContext("/redirect-inward", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/small.xml");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-loop", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/redirect-loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private RssFeedSource permissiveSource() {
        return new RssFeedSource(new OutboundTargetPolicy(true, true));
    }

    @Test
    void anOrdinaryFeedStillFetches() throws Exception {
        FetchResult result = permissiveSource().fetch(SourceConfig.initial(base + "/small.xml"));
        assertThat(result.feedTitle()).isEqualTo("Small Cast");
        assertThat(result.episodes()).hasSize(1);
    }

    @Test
    void refusesABodyThatDeclaresItselfTooLarge() {
        assertThatThrownBy(() -> permissiveSource().fetch(SourceConfig.initial(base + "/declared-huge")))
                .isInstanceOf(FetchException.class);
    }

    @Test
    void refusesABodyThatStreamsPastTheCap() {
        // The one a Content-Length check alone misses: chunked, so there is no length to check.
        assertThatThrownBy(() -> permissiveSource().fetch(SourceConfig.initial(base + "/streamed-huge")))
                .isInstanceOf(FetchException.class);
    }

    @Test
    void givesUpOnASlowDripInsteadOfHoldingTheThreadForever() {
        // The audit held a request open past 75 seconds this way. The budget is 30; allow generous slack and
        // still assert it returned on its own rather than running to the drip's own 300-second length.
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> permissiveSource().fetch(SourceConfig.initial(base + "/slow")))
                .isInstanceOf(FetchException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void refusesARedirectIntoAPrivateAddress() {
        // The bypass this closes: `Redirect.NORMAL` refuses only an HTTPS→HTTP downgrade, so a chain starting
        // on http:// was followed anywhere at all — an attacker-controlled public host 302ing into 127.0.0.1.
        // Front-door validation alone can never catch that, because the front door saw a public host.
        //
        // Modelled with a policy that accepts the first target and refuses the second, because the real shape
        // (public → private) cannot be built from a loopback fixture server.
        OutboundTargetPolicy firstHopOnly = new OutboundTargetPolicy(true, true) {
            private int calls;

            @Override
            public java.net.URI validate(java.net.URI uri) {
                if (calls++ > 0) {
                    throw new IllegalArgumentException(OutboundTargetPolicy.BLOCKED_MESSAGE);
                }
                return super.validate(uri);
            }
        };

        assertThatThrownBy(() -> new RssFeedSource(firstHopOnly)
                .fetch(SourceConfig.initial(base + "/redirect-inward")))
                .isInstanceOf(FetchException.class)
                .hasMessage(OutboundTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void reportsARefusedUrlAsAFetchFailureRatherThanThrowingRuntime() {
        // FeedPipeline only catches FetchException. A stored feed whose host starts resolving somewhere
        // private should back off like any other failing feed, not escape to the scheduler's catch-all.
        RssFeedSource strict = new RssFeedSource(new OutboundTargetPolicy(false, false));
        assertThatThrownBy(() -> strict.fetch(SourceConfig.initial(base + "/small.xml")))
                .isInstanceOf(FetchException.class)
                .hasMessage(OutboundTargetPolicy.BLOCKED_MESSAGE);
    }

    @Test
    void followsAnOrdinaryRedirect() throws Exception {
        FetchResult result = permissiveSource().fetch(SourceConfig.initial(base + "/redirect-inward"));
        assertThat(result.feedTitle()).isEqualTo("Small Cast");
    }

    @Test
    void givesUpOnARedirectLoop() {
        assertThatThrownBy(() -> permissiveSource().fetch(SourceConfig.initial(base + "/redirect-loop")))
                .isInstanceOf(FetchException.class)
                .hasMessageContaining("too many redirects");
    }
}
