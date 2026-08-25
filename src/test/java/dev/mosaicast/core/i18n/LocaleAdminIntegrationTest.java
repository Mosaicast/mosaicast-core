// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The runtime language registry end to end (ARCHITECTURE §12.7): a catalog dropped into
 * {@code MOSAICAST_LOCALES_DIR} is discovered, stays invisible until an admin enables it, and once enabled
 * becomes a language the shell can load and a legal page can be written in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class LocaleAdminIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A drop-in directory holding one language nothing else in the suite knows about. */
    static final Path LOCALES_DIR = createLocalesDir();

    private static Path createLocalesDir() {
        try {
            Path dir = Files.createTempDirectory("mosaicast-locales");
            Files.writeString(dir.resolve("nl.json"),
                    "{\"app.title\": \"Mosaicast\", \"nav.home\": \"Start\"}");
            return dir;
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    @DynamicPropertySource
    static void localesDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.locales-dir", LOCALES_DIR::toString);
    }

    @Autowired
    private TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    @Test
    void aDroppedInLanguageIsFoundButNotOfferedUntilAnAdminEnablesIt() {
        // Anonymous: the public list shows only what the admin has published.
        Map<String, Object> before = rest.getForObject("/api/i18n/locales", Map.class);
        assertThat(codes(before, "ui")).contains("en", "de").doesNotContain("nl");

        // The catalog exists on disk, so serving it would publish a language nobody enabled.
        assertThat(rest.getForEntity("/api/i18n/catalog/nl", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        Map<String, Object> adminView = rest.exchange("/api/admin/i18n", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), Map.class).getBody();
        List<Map<String, Object>> locales = (List<Map<String, Object>>) adminView.get("locales");
        Map<String, Object> dutch = locales.stream()
                .filter(locale -> "nl".equals(locale.get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(dutch.get("origin")).isEqualTo("DROP_IN");
        assertThat(dutch.get("uiEnabled")).isEqualTo(false);
        assertThat(dutch.get("nativeName")).isEqualTo("Nederlands");

        ResponseEntity<String> saved = put(admin, "/api/admin/i18n", Map.of(
                "uiLocales", List.of("en", "de", "nl"),
                "contentLocales", List.of("en", "de", "nl"),
                "defaultLocale", "en"));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = rest.getForObject("/api/i18n/locales", Map.class);
        assertThat(codes(after, "ui")).contains("nl");
        assertThat(rest.getForObject("/api/i18n/catalog/nl", Map.class)).containsEntry("nav.home", "Start");
    }

    @Test
    void theShellCannotBeOfferedALanguageWithNoCatalog() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        ResponseEntity<String> refused = put(admin, "/api/admin/i18n", Map.of(
                "uiLocales", List.of("en", "fr"),
                "contentLocales", List.of("en", "fr"),
                "defaultLocale", "en"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("fr.json");
    }

    @Test
    void theDefaultMustBeALanguageContentCanBeWrittenIn() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        ResponseEntity<String> refused = put(admin, "/api/admin/i18n", Map.of(
                "uiLocales", List.of("en"),
                "contentLocales", List.of("en"),
                "defaultLocale", "sv"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aLegalTranslationCanOnlyBeWrittenInAContentLanguage() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        put(admin, "/api/admin/i18n", Map.of(
                "uiLocales", List.of("en", "de"),
                "contentLocales", List.of("en", "de", "nl"),
                "defaultLocale", "en"));

        ResponseEntity<String> created = rest.exchange("/api/admin/legal", HttpMethod.POST,
                new HttpEntity<>(Map.of("slug", "imprint-loc", "roleMarker", "imprint", "sortOrder", 5),
                        headers(admin)), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Dutch is a content language even though the shell does not offer it — that split is the point.
        assertThat(put(admin, "/api/admin/legal/imprint-loc/translations/nl",
                Map.of("title", "Colofon", "markdown", "# Colofon")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Swedish is not, and a page saved under it would be invisible to every reader and to the editor.
        assertThat(put(admin, "/api/admin/legal/imprint-loc/translations/sv",
                Map.of("title", "Kolofon", "markdown", "# Kolofon")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> payload, String key) {
        return ((List<Map<String, Object>>) payload.get(key)).stream()
                .map(locale -> (String) locale.get("code"))
                .toList();
    }

    private ResponseEntity<String> put(DevLogin.Cookies admin, String path, Object body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers(admin)), String.class);
    }

    private static HttpHeaders headers(DevLogin.Cookies admin) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, admin.session() + "; " + admin.xsrf());
        headers.add("X-XSRF-TOKEN", admin.token());
        return headers;
    }
}
