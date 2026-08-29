// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation.providers;

import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.error.ExternalProviderException;
import dev.mosaicast.core.external.http.ExternalHttpClient;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsManifest;
import dev.mosaicast.core.external.translation.TranslationProvider;
import dev.mosaicast.core.external.translation.TranslationRequest;
import dev.mosaicast.core.external.translation.TranslationResult;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * LibreTranslate, self-hosted or hosted (ARCHITECTURE §12.7).
 *
 * <p>The first provider, and the one worth shipping first: it is free software an operator can run
 * themselves, so a site can translate without sending its text to anybody at all. Its engine is Argos
 * Translate; the REST wrapper is what makes it usable from a JVM without a Python sidecar of our own.
 *
 * <p><strong>The API key is optional, and that is not a convenience.</strong> LibreTranslate ships with
 * {@code keyRequired: false}, so a self-hosted instance on a private network genuinely needs no credential;
 * the same image behind a public URL usually enforces one. A provider that made the key mandatory would be
 * unusable on the first, and one that omitted it would be unusable on the second. Declared optional in both
 * shapes — from the environment (preferred) or typed by the admin — and sent only when there is one.
 */
@Component
public class LibreTranslateProvider implements TranslationProvider {

    /** Stable id: the stored value, the URL segment and the i18n key suffix. */
    public static final String ID = "libretranslate";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ExternalHttpClient http;

    public LibreTranslateProvider(ExternalHttpClient http) {
        this.http = http;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ID,
                ExternalServiceKind.TRANSLATION,
                "LibreTranslate",
                "Free and open-source machine translation. Run it yourself, or point at a hosted instance.",
                "https://libretranslate.com",
                // No privacy URL for the self-hosted case, which is the one this exists to serve: there is
                // no third party to have a policy. An operator pointing at somebody else's instance is
                // choosing that relationship themselves.
                null,
                true,
                false,
                false,
                true,
                "characters",
                60,
                Duration.ofSeconds(20),
                SettingsManifest.of(
                        SettingsField.requiredString("baseUrl", "Base URL",
                                "Where LibreTranslate is reachable, e.g. http://libretranslate:5000. A "
                                        + "private address must also be allow-listed in "
                                        + "MOSAICAST_EXTERNAL_ALLOWED_PRIVATE_ORIGINS.",
                                "http://localhost:5000"),
                        SettingsField.envSecret("apiKeyEnv", "API key (environment)",
                                "Only needed if this instance runs with API keys enabled. Preferred over "
                                        + "the stored key below: a value that is never stored cannot leak "
                                        + "from a database backup.",
                                "API_KEY", false),
                        SettingsField.secret("apiKey", "API key (stored)",
                                "An alternative to the environment variable above, for operators who "
                                        + "cannot restart the app to set one. Stored in the database.",
                                false),
                        SettingsField.positiveInt("requestsPerMinute", "Requests per minute",
                                "How often this site may call the service.", 60)));
    }

    @Override
    public TranslationResult call(TranslationRequest input, ProviderConfig config)
            throws ExternalProviderException {
        ObjectNode body = JSON.createObjectNode();
        body.put("q", input.text());
        body.put("source", input.from());
        body.put("target", input.to());
        body.put("format", input.format() == TranslationRequest.Format.HTML ? "html" : "text");
        // In the body, never a query string: a credential in a URL lands in the upstream's access log.
        apiKey(config).ifPresent(key -> body.put("api_key", key));

        String response = http.postJson(endpoint(config, "/translate"), body.toString(),
                Map.of(), timeout());
        JsonNode parsed = parse(response);

        JsonNode translated = parsed.get("translatedText");
        if (translated == null || !translated.isString()) {
            // A 200 whose body is not what the contract promises is a failure, not an empty translation —
            // storing "" as a translation would look like a deliberate blank to every later reader.
            throw new ExternalProviderException("The service returned no translation");
        }
        return new TranslationResult(translated.stringValue(), detectedLanguage(parsed), ID, false);
    }

    /**
     * A fixed round trip that proves the address and the credential, and translates nothing.
     *
     * <p>{@code /languages} rather than a token translation: the admin's Test button must not be a way to
     * spend budget, and on a metered instance a probe that translated would do exactly that.
     */
    @Override
    public ProbeResult probe(ProviderConfig config) {
        try {
            JsonNode languages = parse(http.get(endpoint(config, "/languages"), Map.of(), timeout()));
            if (!languages.isArray() || languages.isEmpty()) {
                return ProbeResult.failed("The service answered, but listed no languages");
            }
            return ProbeResult.ok("%d languages available".formatted(languages.size()));
        } catch (RuntimeException refused) {
            // Already sanitized by ExternalHttpClient / ExternalTargetPolicy — safe to show an admin.
            return ProbeResult.failed(refused.getMessage());
        }
    }

    /** The languages this instance can translate into, for validating a target before spending a call. */
    public java.util.Set<String> supportedTargets(ProviderConfig config) {
        JsonNode languages = parse(http.get(endpoint(config, "/languages"), Map.of(), timeout()));
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        languages.forEach(language -> {
            JsonNode code = language.get("code");
            if (code != null && code.isString()) {
                codes.add(code.stringValue().toLowerCase(Locale.ROOT));
            }
        });
        return java.util.Set.copyOf(codes);
    }

    /**
     * The credential, from the environment first.
     *
     * <p>Both fields are optional and either may be set. Environment wins when both are: it is the shape the
     * docs recommend, and an operator who has set one there has said where they want it to live.
     */
    private static Optional<String> apiKey(ProviderConfig config) {
        Optional<String> fromEnv = config.secret("apiKeyEnv");
        return fromEnv.isPresent() ? fromEnv : config.secret("apiKey");
    }

    private static String endpoint(ProviderConfig config, String path) {
        String base = config.string("baseUrl").trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    /** The call budget. One place, so the descriptor's declared timeout is the one actually applied. */
    private Duration timeout() {
        return descriptor().defaultTimeout();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (RuntimeException malformed) {
            // The body is not echoed: on a misdirected URL this is whatever else is listening on that port.
            throw new ExternalProviderException("The service returned something that is not JSON");
        }
    }

    /** LibreTranslate reports a detection only when asked to detect; absent is the ordinary case. */
    private static String detectedLanguage(JsonNode parsed) {
        JsonNode detected = parsed.get("detectedLanguage");
        if (detected == null || !detected.isObject()) {
            return null;
        }
        JsonNode language = detected.get("language");
        return language != null && language.isString() ? language.stringValue() : null;
    }
}
