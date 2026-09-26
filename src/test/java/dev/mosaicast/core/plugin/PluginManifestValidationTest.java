// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.plugin.api.PlatformApi;
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
                {"id":"sample","version":"1.0.0","platformApi":"HOST_API","name":"Sample",
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
        // Same major.minor as the host, different patch — accepted. Derived rather than written out, so the
        // next SDK minor does not turn this case into a silent duplicate of the rejection test below.
        String otherPatch =
                PlatformApi.VERSION.substring(0, PlatformApi.VERSION.lastIndexOf('.')) + ".99";
        assertThatCode(parse(base(otherPatch, "doc", "sidebar"))::validate).doesNotThrowAnyException();
    }

    @Test
    void incompatiblePlatformApiIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.2.0", "doc", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("platformApi");
    }

    @Test
    void declaredSchemaStorageIsRejected() throws Exception {
        assertThatThrownBy(parse(base(PlatformApi.VERSION, "schema", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void unknownSlotPlacementIsRejected() throws Exception {
        assertThatThrownBy(parse(base(PlatformApi.VERSION, "doc", "nowhere"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("placement");
    }

    @Test
    void aUsableFrontendEntryIsAccepted() throws Exception {
        for (String entry : new String[] {"s.js", "assets/index-a1b2.js", "a/b/c.mjs", "..leading.js"}) {
            assertThatCode(parse(withEntry(entry))::validate)
                    .describedAs(entry)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void anEntryThatWouldAddressSomethingElseIsRejected() throws Exception {
        // It is the one manifest string that becomes a path and had no grammar (core#181). The shell builds
        // /plugins/<id>/assets/<entry> by interpolation, so `../` or a query addresses something other than
        // what the author wrote. Refused rather than normalised, exactly as a nav path is: loading a
        // different file than the one declared is worse than saying the declaration was wrong.
        for (String entry : new String[] {
            "/absolute.js", "../../admin", "a/../b.js", "./a.js", "s.js?v=2", "s.js#x", "a//b.js", "a b.js",
        }) {
            assertThatThrownBy(parse(withEntry(entry))::validate)
                    .describedAs(entry)
                    .isInstanceOf(PluginValidationException.class)
                    .hasMessageContaining("frontend.entry");
        }
    }

    @Test
    void aBackendOnlyPluginNeedsNoEntryAtAll() throws Exception {
        assertThatCode(parse("""
                {"id":"headless","version":"1.0.0","platformApi":"HOST_API","name":"Headless",
                 "storage":"doc"}
                """)::validate).doesNotThrowAnyException();
    }

    /** The compatible manifest above with one {@code frontend.entry} substituted in. */
    private String withEntry(String entry) {
        return """
                {"id":"sample","version":"1.0.0","platformApi":"HOST_API","name":"Sample",
                 "frontend":{"entry":"%s","elements":["s-card"]},
                 "slots":[{"scope":"site","element":"s-card","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc"}
                """.formatted(entry);
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
    void aFieldWithOptionsAcceptsOnlyThoseOptions() throws Exception {
        // Before options existed, a field its plugin understood as exactly two words was a free-text box:
        // a typo passed validation, was stored, and then fell back silently at read time.
        PluginManifest manifest = parse(withConfig("""
                {"rankBy":{"type":"string","default":"lines","options":[
                   {"value":"lines","label":{"en":"Lines","de":"Reihen"}},
                   {"value":"fields","label":{"en":"Fields","de":"Felder"}}]}}
                """));
        assertThatCode(manifest::validate).doesNotThrowAnyException();

        PluginManifest.ConfigField field = manifest.config().get("rankBy");
        assertThat(field.isEnum()).isTrue();
        assertThat(field.accepts(mapper.readTree("\"fields\""))).isTrue();
        assertThat(field.accepts(mapper.readTree("\"linez\""))).isFalse();
        // Clearing an override is still how an operator falls back to the default.
        assertThat(field.accepts(mapper.readTree("null"))).isTrue();
    }

    @Test
    void aDefaultOutsideTheDeclaredOptionsIsRejected() throws Exception {
        assertThatThrownBy(parse(withConfig("""
                {"rankBy":{"type":"string","default":"columns","options":[
                   {"value":"lines"},{"value":"fields"}]}}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("not one of its options");
    }

    @Test
    void anOptionOfTheWrongTypeIsRejected() throws Exception {
        // The type still governs what is stored; options narrow it, they do not replace it.
        assertThatThrownBy(parse(withConfig("""
                {"size":{"type":"number","default":3,"options":[{"value":3},{"value":"four"}]}}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void aFieldCarriesItsOwnLabelAndDescription() throws Exception {
        // Without these the form can only show the identifier a plugin author chose, and the plugin cannot
        // make up the difference: it is forbidden from building its own config UI.
        PluginManifest manifest = parse(withConfig("""
                {"ingestIntervalSeconds":{"type":"number","default":60,
                  "label":{"en":"Ingest interval","de":"Abrufintervall"},
                  "description":{"en":"How often the source is re-read, in seconds."}},
                 "rankBy":{"type":"string","default":"lines","label":"Ranking"}}
                """));
        assertThatCode(manifest::validate).doesNotThrowAnyException();

        // Carried verbatim: the host does not read prose, it hands it to the browser that knows the locale.
        assertThat(manifest.config().get("ingestIntervalSeconds").label().get("de").stringValue())
                .isEqualTo("Abrufintervall");
        assertThat(manifest.config().get("rankBy").label().stringValue()).isEqualTo("Ranking");
        assertThat(manifest.config().get("rankBy").description()).isNull();
    }

    @Test
    void aLabelThatIsNeitherAStringNorALocaleObjectIsRejected() throws Exception {
        // Only the shape is checked. A label the host cannot read is cosmetic, but a number where prose
        // belongs is a mistake worth telling the author about while they still have the manifest open.
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"string\",\"default\":\"a\",\"label\":7}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("neither a string nor a locale object");
    }

    @Test
    void numericAndLengthBoundsAreEnforcedAndNamed() throws Exception {
        PluginManifest manifest = parse(withConfig("""
                {"interval":{"type":"number","default":60,"min":10,"max":3600,"step":1},
                 "greeting":{"type":"string","default":"Hi","minLength":1,"maxLength":5}}
                """));
        assertThatCode(manifest::validate).doesNotThrowAnyException();

        PluginManifest.ConfigField interval = manifest.config().get("interval");
        tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(interval.rejection(json.readTree("0"))).isEqualTo("must be at least 10");
        assertThat(interval.rejection(json.readTree("9999"))).isEqualTo("must be at most 3600");
        assertThat(interval.rejection(json.readTree("10.5"))).isEqualTo("must be 10 plus a multiple of 1");
        assertThat(interval.rejection(json.readTree("60"))).isNull();
        PluginManifest.ConfigField greeting = manifest.config().get("greeting");
        assertThat(greeting.rejection(json.readTree("\"\""))).isEqualTo("must be at least 1 characters");
        assertThat(greeting.rejection(json.readTree("\"Hello!\""))).isEqualTo("must be at most 5 characters");
    }

    @Test
    void boundsThatCannotMeanAnythingAreRefusedAtLoad() throws Exception {
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"string\",\"min\":1}}"))::validate)
                .isInstanceOf(PluginValidationException.class).hasMessageContaining("only a number field");
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"number\",\"maxLength\":3}}"))::validate)
                .isInstanceOf(PluginValidationException.class).hasMessageContaining("only a string field");
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"number\",\"min\":5,\"max\":1}}"))::validate)
                .isInstanceOf(PluginValidationException.class).hasMessageContaining("min above max");
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"number\",\"step\":0}}"))::validate)
                .isInstanceOf(PluginValidationException.class).hasMessageContaining("step must be positive");
        // The plugin would boot on a value its own form refuses.
        assertThatThrownBy(parse(withConfig(
                "{\"x\":{\"type\":\"number\",\"default\":0,\"min\":1}}"))::validate)
                .isInstanceOf(PluginValidationException.class).hasMessageContaining("default must be at least 1");
    }

    @Test
    void aFieldWithoutOptionsStaysFreeForm() throws Exception {
        PluginManifest manifest = parse(withConfig("{\"x\":{\"type\":\"string\",\"default\":\"a\"}}"));
        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.config().get("x").isEnum()).isFalse();
        assertThat(manifest.config().get("x").accepts(mapper.readTree("\"anything\""))).isTrue();
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
    void aPluginMayLabelACategoryItIntroduces() throws Exception {
        // core#177 / SDK 0.16.0: the name a visitor consents under, in their language.
        PluginManifest manifest = parse(withConsent("""
                {"services":[{"id":"m","name":"Mastodon","category":"social"}],
                 "categoryLabels":{"social":{"label":{"en":"Social media","de":"Soziale Medien"},
                                              "hint":"Posts embedded from social networks."}}}
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.consent().categoryLabelsOrEmpty().get("social").label().get("de").asString())
                .isEqualTo("Soziale Medien");
    }

    @Test
    void aCategoryLabelIsRefusedForAHostCategoryForNothingOrWithoutText() throws Exception {
        // Relabelling the host's own category would reword what every plugin's visitors consent to.
        assertThatThrownBy(parse(withConsent("""
                {"services":[{"id":"p","name":"P","category":"analytics"}],
                 "categoryLabels":{"analytics":{"label":"Harmless numbers"}}}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("cannot relabel");
        // A label for a category no service declares is a typo that would otherwise go unnoticed.
        assertThatThrownBy(parse(withConsent("""
                {"services":[{"id":"p","name":"P","category":"social"}],
                 "categoryLabels":{"socail":{"label":"Social media"}}}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("none of this plugin's consent services declares");
        // An empty label puts the bare id back on screen by another route.
        assertThatThrownBy(parse(withConsent("""
                {"services":[{"id":"p","name":"P","category":"social"}],
                 "categoryLabels":{"social":{"label":{"en":"  "}}}}
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("no label text");
    }

    @Test
    void aTagsBlockThatAsksForNothingIsRejected() throws Exception {
        // ctx.tags would be non-null, because the block is there, and every call through it would refuse.
        // Omitting the block is how a plugin declares no tag surface, and it is already the default.
        assertThatThrownBy(parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "tags":{"readsVocabulary":false,"writesEpisodes":false}}
                """)::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("tags");
    }

    @Test
    void theTagFlagsDefaultToReadingButNotWritingEpisodes() throws Exception {
        // Declaring the block at all is asking for the read surface; tagging an episode changes the shell's
        // filters and what core recommends, so that half stays opt-in.
        PluginManifest manifest = parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "tags":{}}
                """);

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.declaresTags()).isTrue();
        assertThat(manifest.readsTagVocabulary()).isTrue();
        assertThat(manifest.writesEpisodeTags()).isFalse();
    }

    @Test
    void anExternalBlockDeclaresItsKindsAndDefaultsTheFloorToPodcaster() throws Exception {
        PluginManifest manifest = parse(withExternal("""
                "kinds":["translation"]
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.declaresExternal()).isTrue();
        assertThat(manifest.usesExternalKind(ExternalServiceKind.TRANSLATION)).isTrue();
        // The same floor `data.writableBy` defaults to: an external call spends money, so it is not a thing
        // an unspecified manifest gets to hand to everybody.
        assertThat(manifest.externalUsedBy()).isEqualTo("podcaster");
    }

    @Test
    void aPluginThatDeclaredNoExternalBlockUsesNoKind() throws Exception {
        PluginManifest manifest = parse(base(PlatformApi.VERSION, "doc", "sidebar"));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.declaresExternal()).isFalse();
        assertThat(manifest.usesExternalKind(ExternalServiceKind.TRANSLATION)).isFalse();
        // Still answers the floor question, so a caller never has to null-check before comparing.
        assertThat(manifest.externalUsedBy()).isEqualTo("podcaster");
    }

    @Test
    void anExternalBlockThatDeclaresNoKindsIsRejected() throws Exception {
        // The same call the `tags` block asking for nothing gets: it would produce a surface that exists and
        // grants nothing, and omitting the block already means no external surface.
        assertThatThrownBy(parse(withExternal("""
                "kinds":[]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("no kinds");
    }

    @Test
    void anUnknownExternalKindIsRejected() throws Exception {
        // Refused rather than dropped. `platformApi` is an exact major.minor match, so within one contract
        // version the vocabulary is closed — a name nothing answers to is a typo, and dropping it would let
        // the manifest claim a capability the host silently grants none of.
        assertThatThrownBy(parse(withExternal("""
                "kinds":["transcription"]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("transcription");
    }

    @Test
    void anExternalFloorThatIsNotARoleIsRejected() throws Exception {
        assertThatThrownBy(parse(withExternal("""
                "kinds":["translation"], "usedBy":"editor"
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("usedBy");
    }

    @Test
    void anAnonymousExternalFloorIsLegal() throws Exception {
        // Unlike `data.writableBy`, which refuses it outright. §16 calls this legal and almost always wrong:
        // a self-hosted LibreTranslate costs nothing per call, and an operator running one should be able to
        // put a translate button in front of visitors. It is warned about at load, not refused.
        PluginManifest manifest = parse(withExternal("""
                "kinds":["translation"], "usedBy":"anonymous"
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.externalUsedBy()).isEqualTo("anonymous");
    }

    @Test
    void navEntriesNeedAPageToLinkInto() throws Exception {
        // Every entry would be a link into a 404. Failing at load names the contradiction; the alternative
        // is a menu item that is broken for as long as nobody clicks it.
        assertThatThrownBy(parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "nav":[{"path":"","label":"P"}]}
                """)::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("page");
    }

    @Test
    void aNavPathMayNotClimbOutOfItsPlugin() throws Exception {
        // Refused rather than quietly normalised: rewriting an author's declaration into a *different* URL
        // and then linking to it is worse than telling them it was wrong.
        assertThatThrownBy(parse(withPage("""
                "nav":[{"path":"../../admin","label":"Sneaky"}]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("plain subpath");

        assertThatThrownBy(parse(withPage("""
                "nav":[{"path":"/absolute","label":"Sneaky"}]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("plain subpath");
    }

    @Test
    void aNavEntryNeedsALabelAndAUniquePath() throws Exception {
        assertThatThrownBy(parse(withPage("""
                "nav":[{"path":"a","label":"  "}]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("label");

        assertThatThrownBy(parse(withPage("""
                "nav":[{"path":"a","label":"One"},{"path":"a","label":"Two"}]
                """))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void anUnknownIconNeverRejectsThePlugin() throws Exception {
        // Placement rejects because it decides whether a thing renders at all; an icon is decoration, and
        // refusing to load a whole plugin over a mistyped one would be disproportionate. The host also has
        // no icon list to check against — the palette lives in generated CSS.
        PluginManifest manifest = parse(withPage("""
                "nav":[{"path":"","label":"Wiki","icon":"definitely-not-an-icon"}]
                """));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.navOrEmpty().getFirst().icon()).isEqualTo("definitely-not-an-icon");
    }

    @Test
    void aPagePluginWithoutNavIsStillFine() throws Exception {
        // The back-compat case: every page plugin that exists today declares no nav, and each must keep
        // loading — the host synthesises a default entry for them rather than asking for a re-release.
        PluginManifest manifest = parse(withPage(null));

        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.navOrEmpty()).isEmpty();
        assertThat(manifest.declaresPage()).isTrue();
    }

    /** A manifest that declares a `page` slot, plus whatever extra top-level JSON the case needs. */
    private static String withPage(String extra) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "slots":[{"scope":"site","element":"p-page","placement":"page"}]
                 %s}
                """.formatted(extra == null ? "" : "," + extra);
    }

    @Test
    void creditFieldsAreOptionalAndNeverValidated() throws Exception {
        // Both directions have to hold, because credit is not a correctness concern. A plugin written
        // before these fields existed must keep loading — it would otherwise be broken by a release that
        // only added a line to an About page. And an unparseable licence string is still a working plugin,
        // so `validate()` deliberately says nothing about any of them.
        PluginManifest bare = parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]}}
                """);

        assertThatCode(bare::validate).doesNotThrowAnyException();
        assertThat(bare.license()).isNull();
        assertThat(bare.author()).isNull();
        assertThat(bare.homepage()).isNull();
        assertThat(bare.attribution()).isNull();

        PluginManifest credited = parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "license":"not-an-spdx-id","author":"A Person",
                 "homepage":"https://example.test/p","attribution":"https://example.test/thanks"}
                """);

        assertThatCode(credited::validate).doesNotThrowAnyException();
        assertThat(credited.license()).isEqualTo("not-an-spdx-id");
        assertThat(credited.author()).isEqualTo("A Person");
        assertThat(credited.homepage()).isEqualTo("https://example.test/p");
        assertThat(credited.attribution()).isEqualTo("https://example.test/thanks");
    }

    @Test
    void declaringNoConsentAtAllIsFine() throws Exception {
        // The banner-free default: a plugin that contacts no third party says nothing.
        assertThatCode(parse("""
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
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

    /**
     * Parses a fixture manifest, substituting the host's own {@code platformApi} for the {@code HOST_API}
     * token.
     *
     * <p>A literal version in a text block goes stale on the next SDK minor and takes every test in this
     * class down with it — which is exactly what 0.10.0 did to the hardcoded {@code 0.9.0}. These cases are
     * about slots, storage and config; only {@link #incompatiblePlatformApiIsRejected()} is about the
     * version, and it states its own.
     */
    private PluginManifest parse(String json) throws Exception {
        return mapper.readValue(json.replace("HOST_API", PlatformApi.VERSION), PluginManifest.class);
    }

    private static String base(String platformApi, String storage, String placement) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"%s","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"%s","visibleTo":"anonymous"}],
                 "storage":"%s","config":{},"consent":{"services":[]}}
                """.formatted(platformApi, placement, storage);
    }

    /** A valid manifest carrying the given {@code external} body, to isolate external validation. */
    private static String withExternal(String external) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "backend":{"basePath":"/api/plugins/p","extensions":[]},
                 "external":{%s}}
                """.formatted(external);
    }

    /** A valid manifest carrying the given {@code config} block, to isolate config validation. */
    private static String withConfig(String config) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc","config":%s,"consent":{"services":[]}}
                """.formatted(config);
    }

    /** A valid manifest carrying the given {@code consent} block, to isolate consent validation. */
    private static String withConsent(String consent) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "slots":[],"storage":"doc","config":{},"consent":%s}
                """.formatted(consent);
    }

    /** A valid manifest carrying the given {@code data} block, to isolate data validation. */
    private static String withData(String data) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"HOST_API","name":"P",
                 "slots":[],"storage":"doc","config":{},"data":%s}
                """.formatted(data);
    }
}
