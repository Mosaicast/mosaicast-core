// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.error.ExternalProviderException;
import dev.mosaicast.core.external.error.ExternalTimeoutException;
import dev.mosaicast.core.external.http.ExternalHttpClient;
import dev.mosaicast.core.external.http.ExternalTargetPolicy;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.translation.providers.LibreTranslateProvider;
import dev.mosaicast.core.feed.OutboundTargetPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The LibreTranslate provider against a loopback {@link HttpServer} — the pattern {@code RssFeedSourceLimitsTest}
 * already uses, so CI never reaches a real service.
 *
 * <p>The allow-list this constructs with ({@code http://127.0.0.1:<port>}) doubles as executable
 * documentation of the production configuration: a self-hosted translator is a private origin and has to be
 * named explicitly.
 */
class LibreTranslateProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> requestUris = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(String path, Consumer<HttpExchange> handler) {
        server.createContext(path, exchange -> {
            requestUris.add(exchange.getRequestURI().toString());
            try (InputStream body = exchange.getRequestBody()) {
                requestBodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            handler.accept(exchange);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (IOException problem) {
            throw new IllegalStateException(problem);
        }
    }

    private LibreTranslateProvider provider() {
        ExternalTargetPolicy targets =
                new ExternalTargetPolicy(new OutboundTargetPolicy(false, false), baseUrl);
        return new LibreTranslateProvider(new ExternalHttpClient(targets));
    }

    /** Minimal config: a base URL and, optionally, a credential. */
    private ProviderConfig config(String apiKeyEnv, String apiKeyStored) {
        return new ProviderConfig() {
            @Override
            public String string(String key) {
                return baseUrl;
            }

            @Override
            public Optional<String> optionalString(String key) {
                return Optional.of(baseUrl);
            }

            @Override
            public int integer(String key) {
                return 60;
            }

            @Override
            public double decimal(String key) {
                return 0;
            }

            @Override
            public boolean bool(String key) {
                return false;
            }

            @Override
            public Optional<String> secret(String key) {
                return Optional.ofNullable("apiKeyEnv".equals(key) ? apiKeyEnv : apiKeyStored);
            }

            @Override
            public String fingerprint() {
                return "test";
            }
        };
    }

    @Test
    void translatesAndReportsNoDetectionWhenTheSourceWasStated() {
        handle("/translate", exchange ->
                respond(exchange, 200, "{\"translatedText\":\"De kraken dook op.\"}"));

        TranslationResult result = provider()
                .call(TranslationRequest.of("The kraken surfaced.", "en", "nl"), config(null, null));

        assertThat(result.text()).isEqualTo("De kraken dook op.");
        // The real service only reports a detection when asked to detect; absent must not become "".
        assertThat(result.detectedSourceLanguage()).isNull();
        assertThat(result.providerId()).isEqualTo(LibreTranslateProvider.ID);
        assertThat(result.fromCache()).isFalse();
        assertThat(requestBodies.getFirst()).contains("\"source\":\"en\"").contains("\"target\":\"nl\"");
    }

    @Test
    void carriesTheDetectedLanguageBackWhenTheProviderReportsOne() {
        handle("/translate", exchange -> respond(exchange, 200,
                "{\"detectedLanguage\":{\"confidence\":100.0,\"language\":\"de\"},"
                        + "\"translatedText\":\"The kraken appeared.\"}"));

        TranslationResult result = provider()
                .call(TranslationRequest.of("Der Kraken tauchte auf.", "en"), config(null, null));

        assertThat(result.detectedSourceLanguage()).isEqualTo("de");
        assertThat(requestBodies.getFirst()).contains("\"source\":\"auto\"");
    }

    @Test
    void worksWithNoCredentialAtAll() {
        // The default self-hosted instance runs keyRequired:false. No key must mean no api_key field,
        // not an empty one — some deployments reject a blank key outright.
        handle("/translate", exchange -> respond(exchange, 200, "{\"translatedText\":\"x\"}"));

        provider().call(TranslationRequest.of("hi", "nl"), config(null, null));

        assertThat(requestBodies.getFirst()).doesNotContain("api_key");
    }

    @Test
    void sendsACredentialInTheBodyAndNeverInTheUrl() {
        handle("/translate", exchange -> respond(exchange, 200, "{\"translatedText\":\"x\"}"));

        provider().call(TranslationRequest.of("hi", "nl"), config(null, "sk-live-1234"));

        assertThat(requestBodies.getFirst()).contains("\"api_key\":\"sk-live-1234\"");
        // A credential in a query string lands in the upstream's access log.
        assertThat(requestUris.getFirst()).doesNotContain("sk-live-1234");
    }

    @Test
    void theEnvironmentCredentialWinsOverTheStoredOne() {
        handle("/translate", exchange -> respond(exchange, 200, "{\"translatedText\":\"x\"}"));

        provider().call(TranslationRequest.of("hi", "nl"), config("from-env", "from-db"));

        assertThat(requestBodies.getFirst()).contains("from-env").doesNotContain("from-db");
    }

    @Test
    void anUpstreamErrorNeverLeaksItsBody() {
        // A LibreTranslate error body echoes the request — api_key included.
        handle("/translate", exchange -> respond(exchange, 403,
                "{\"error\":\"Invalid API key: sk-live-1234\"}"));

        assertThatThrownBy(() -> provider().call(TranslationRequest.of("hi", "nl"), config(null, "sk-live-1234")))
                .isInstanceOf(ExternalProviderException.class)
                .hasMessageContaining("403")
                .hasMessageNotContaining("sk-live-1234")
                .hasMessageNotContaining("Invalid API key");
    }

    @Test
    void aRedirectIsRefusedRatherThanFollowed() {
        // A translation endpoint has no business redirecting, and following one is how a public host
        // reaches something private.
        handle("/translate", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            respond(exchange, 302, "");
        });

        assertThatThrownBy(() -> provider().call(TranslationRequest.of("hi", "nl"), config(null, null)))
                .isInstanceOf(ExternalProviderException.class)
                .hasMessageContaining("redirected");
    }

    @Test
    void aTwoHundredThatIsNotJsonIsAFailure() {
        // What a misdirected URL looks like: something else entirely is listening on that port.
        handle("/translate", exchange -> respond(exchange, 200, "<html>nginx</html>"));

        assertThatThrownBy(() -> provider().call(TranslationRequest.of("hi", "nl"), config(null, null)))
                .isInstanceOf(ExternalProviderException.class)
                .hasMessageContaining("not JSON");
    }

    @Test
    void aTwoHundredWithNoTranslationIsAFailureRatherThanAnEmptyString() {
        // Storing "" as a translation would read as a deliberate blank to everyone downstream.
        handle("/translate", exchange -> respond(exchange, 200, "{\"somethingElse\":true}"));

        assertThatThrownBy(() -> provider().call(TranslationRequest.of("hi", "nl"), config(null, null)))
                .isInstanceOf(ExternalProviderException.class)
                .hasMessageContaining("no translation");
    }

    @Test
    void aDribblingResponseHitsTheWallClockBudget() throws IOException {
        // The failure HttpRequest.timeout does not catch: headers arrive promptly, then the body trickles.
        handle("/translate", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(" ".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    Thread.sleep(500);
                }
                exchange.close();
            } catch (IOException | InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });

        ExternalTargetPolicy targets =
                new ExternalTargetPolicy(new OutboundTargetPolicy(false, false), baseUrl);
        ExternalHttpClient client = new ExternalHttpClient(targets);

        assertThatThrownBy(() -> client.postJson(baseUrl + "/translate", "{}", java.util.Map.of(),
                java.time.Duration.ofSeconds(2)))
                .isInstanceOf(ExternalTimeoutException.class);
    }

    @Test
    void theProbeAsksForLanguagesRatherThanTranslatingSomething() {
        // The Test button must not be a way to spend budget on a metered instance.
        handle("/languages", exchange -> respond(exchange, 200,
                "[{\"code\":\"en\",\"name\":\"English\"},{\"code\":\"nl\",\"name\":\"Dutch\"}]"));

        ExternalProvider.ProbeResult result = provider().probe(config(null, null));

        assertThat(result.ok()).isTrue();
        assertThat(result.detail()).contains("2 languages");
        assertThat(requestUris).allMatch(uri -> uri.startsWith("/languages"));
    }

    @Test
    void theProbeReportsAFailureInsteadOfThrowing() {
        handle("/languages", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));

        ExternalProvider.ProbeResult result = provider().probe(config(null, null));

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).doesNotContain("boom");
    }

    @Test
    void aPrivateTargetThatWasNotAllowListedIsRefusedBeforeAnyRequest() {
        ExternalTargetPolicy noneAllowed =
                new ExternalTargetPolicy(new OutboundTargetPolicy(false, false), "");
        LibreTranslateProvider refused = new LibreTranslateProvider(new ExternalHttpClient(noneAllowed));
        handle("/translate", exchange -> respond(exchange, 200, "{\"translatedText\":\"x\"}"));

        assertThatThrownBy(() -> refused.call(TranslationRequest.of("hi", "nl"), config(null, null)))
                .hasMessage(ExternalTargetPolicy.BLOCKED_MESSAGE);
        assertThat(requestBodies).as("nothing was sent").isEmpty();
    }
}
