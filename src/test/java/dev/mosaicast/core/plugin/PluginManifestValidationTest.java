// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginManifest} parsing and validation (ARCHITECTURE §7.2) — the stability anchor:
 * a compatible manifest passes; an incompatible {@code platformApi}, declared schema, or unknown slot
 * placement is rejected (so the loader disables that plugin, §7.8).
 */
class PluginManifestValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compatibleManifestValidates() throws Exception {
        PluginManifest manifest = parse("""
                {"id":"sample","version":"1.0.0","platformApi":"0.7.0","name":"Sample",
                 "backend":{"basePath":"/api/plugins/sample","extensions":["X"]},
                 "frontend":{"entry":"s.js","elements":["s-card"]},
                 "slots":[{"scope":"site","element":"s-card","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc","config":{},"consent":{"services":[]}}
                """);
        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.id()).isEqualTo("sample");
    }

    @Test
    void patchDifferenceIsCompatible() throws Exception {
        // Same major.minor as the host (0.7.x), different patch — accepted.
        assertThatCode(parse(base("0.7.9", "doc", "sidebar"))::validate).doesNotThrowAnyException();
    }

    @Test
    void incompatiblePlatformApiIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.2.0", "doc", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("platformApi");
    }

    @Test
    void declaredSchemaStorageIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.7.0", "schema", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void unknownSlotPlacementIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.7.0", "doc", "nowhere"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("placement");
    }

    @Test
    void declaredConfigFieldsAreAccepted() throws Exception {
        assertThatCode(parse(withConfig("""
                {"minutes":{"type":"number","default":30,"editableBy":"podcaster"},
                 "label":{"type":"string","default":"hi"},
                 "loud":{"type":"boolean","default":true,"editableBy":"admin"}}
                """))::validate).doesNotThrowAnyException();
    }

    @Test
    void unknownConfigTypeIsRejected() throws Exception {
        // The host renders the form and type-checks admin input from this declaration, so a type it cannot
        // render is a load-time rejection rather than a broken admin page.
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"colour\",\"default\":\"red\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void unknownEditableByIsRejected() throws Exception {
        assertThatThrownBy(parse(withConfig(
                "{\"x\":{\"type\":\"string\",\"default\":\"a\",\"editableBy\":\"fan\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("editableBy");
    }

    @Test
    void defaultMustMatchDeclaredType() throws Exception {
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"number\",\"default\":\"thirty\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("default");
    }

    @Test
    void fieldWithoutEditableByDefaultsToAdmin() throws Exception {
        PluginManifest manifest = parse(withConfig("{\"x\":{\"type\":\"string\",\"default\":\"a\"}}"));
        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.config().get("x").editableByOrDefault())
                .isEqualTo(PluginManifest.EDITABLE_BY_ADMIN);
    }

    @Test
    void theLegacyConsentFormIsRejected() throws Exception {
        // A plugin migrated per the SDK guide declares services[]; one that did not must fail loudly rather
        // than load with its consent declaration silently dropped — that would mean no banner and no CSP
        // origins for third parties it actually contacts.
        assertThatThrownBy(parse(withConsent("{\"categories\":[\"analytics\"],"
                + "\"externalSources\":[\"https://plausible.example\"]}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("services[]")
                .hasMessageContaining("0.4");
    }

    @Test
    void aValidServiceDeclarationIsAccepted() throws Exception {
        assertThatCode(parse(withConsent("""
                {"services":[{"id":"plausible","name":"Plausible Analytics",
                 "provider":"Plausible Insights OÜ","category":"analytics",
                 "privacyUrl":"https://plausible.io/privacy","hosts":["https://plausible.example"],
                 "thirdCountryTransfer":false,
                 "storage":[{"name":"pa","type":"cookie","purpose":"counts a visit","duration":"24 hours"}]}]}
                """))::validate).doesNotThrowAnyException();
    }

    @Test
    void aHostWithoutASchemeIsRejected() throws Exception {
        // hosts double as CSP origins, and an origin without a scheme silently never matches — the plugin's
        // embeds would fail to load with consent granted and nothing to explain why.
        assertThatThrownBy(parse(withConsent("""
                {"services":[{"id":"p","name":"P","category":"analytics","hosts":["plausible.example"]}]}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("needs a scheme");
    }

    @Test
    void aServiceWithoutANameOrCategoryIsRejected() throws Exception {
        assertThatThrownBy(parse(withConsent(
                "{\"services\":[{\"id\":\"p\",\"category\":\"analytics\"}]}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("no name");
        assertThatThrownBy(parse(withConsent(
                "{\"services\":[{\"id\":\"p\",\"name\":\"P\"}]}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("no category");
    }

    @Test
    void optionalServiceFieldsMayBeOmitted() throws Exception {
        // Jackson 3 refuses to map a missing value onto a primitive, so a boxed flag is what makes an
        // omitted `thirdCountryTransfer` parse at all — a plugin should not have to spell out every field.
        PluginManifest manifest = parse(withConsent("""
                {"services":[{"id":"p","name":"P","category":"analytics"}]}
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.consent().servicesOrEmpty().getFirst().transfersToThirdCountry()).isFalse();
    }

    @Test
    void declaringNoConsentAtAllIsFine() throws Exception {
        // The banner-free default: a plugin that contacts no third party says nothing.
        assertThatCode(parse("""
                {"id":"p","version":"1.0.0","platformApi":"0.7.0","name":"P",
                 "slots":[],"storage":"doc","config":{}}
                """)::validate).doesNotThrowAnyException();
    }

    @Test
    void backendOwnedAcceptsAnExactKeyAPrefixAndABareStar() throws Exception {
        PluginManifest manifest = parse(withData("""
                {"readableBy":"anonymous","writableBy":"podcaster",
                 "backendOwned":["stats","agg:*","*"]}
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.data().backendOwnedOrEmpty()).containsExactly("stats", "agg:*", "*");
    }

    @Test
    void aMalformedBackendOwnedEntryIsRejected() {
        // Rejected, not dropped: a dropped entry would load a plugin whose manifest claims a key is the
        // backend's while the host enforces nothing — the worst way for a security declaration to fail.
        // A `*` in the middle, an empty entry, leading whitespace, a second star, an over-long prefix,
        // and a JSON null.
        for (String entry : new String[] {"\"a*b\"", "\"\"", "\" stats\"", "\"**\"",
                "\"" + "x".repeat(201) + "\"", "null"}) {
            assertThatThrownBy(() -> parse(withData("""
                    {"writableBy":"podcaster","backendOwned":[%s]}
                    """.formatted(entry))).validate())
                    .isInstanceOf(PluginValidationException.class)
                    .hasMessageContaining("backendOwned");
        }
    }

    @Test
    void declaringNoBackendOwnedIsFine() throws Exception {
        PluginManifest manifest = parse(withData("""
                {"readableBy":"anonymous","writableBy":"podcaster"}
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.data().backendOwnedOrEmpty()).isEmpty();
    }

    private PluginManifest parse(String json) throws Exception {
        return mapper.readValue(json, PluginManifest.class);
    }

    private static String base(String platformApi, String storage, String placement) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"%s","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"%s","visibleTo":"anonymous"}],
                 "storage":"%s","config":{},"consent":{"services":[]}}
                """.formatted(platformApi, placement, storage);
    }

    /** A valid manifest carrying the given {@code config} block, to isolate config validation. */
    private static String withConfig(String config) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"0.7.0","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc","config":%s,"consent":{"services":[]}}
                """.formatted(config);
    }

    /** A valid manifest carrying the given {@code consent} block, to isolate consent validation. */
    private static String withConsent(String consent) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"0.7.0","name":"P",
                 "slots":[],"storage":"doc","config":{},"consent":%s}
                """.formatted(consent);
    }

    /** A valid manifest carrying the given {@code data} block, to isolate data validation. */
    private static String withData(String data) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"0.7.0","name":"P",
                 "slots":[],"storage":"doc","config":{},"data":%s}
                """.formatted(data);
    }
}
