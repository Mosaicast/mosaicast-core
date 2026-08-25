// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.external.settings.SettingsField.Option;
import dev.mosaicast.core.external.settings.SettingsField.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.StringNode;

/** What a provider setting will and will not accept (ARCHITECTURE §12.7). */
class SettingsFieldTest {

    private static String rejection(SettingsField field, JsonNode value) {
        assertThat(field.accepts(value)).isInstanceOf(Result.Rejected.class);
        return ((Result.Rejected) field.accepts(value)).reason();
    }

    private static void accepts(SettingsField field, JsonNode value) {
        assertThat(field.accepts(value)).isInstanceOf(Result.Ok.class);
    }

    @Test
    void jsonNullAlwaysPasses() {
        // It is how an admin clears an override and falls back to the default — the same convention
        // plugin_config uses. A required field is no exception: clearing is not the same as submitting bad
        // data, and what happens with nothing set is the readiness check's problem, not the field's.
        accepts(SettingsField.requiredString("baseUrl", "Base URL", "", null), NullNode.getInstance());
        accepts(SettingsField.positiveInt("rpm", "Requests per minute", "", 60), NullNode.getInstance());
        accepts(SettingsField.secret("apiKey", "API key", ""), null);
    }

    @Test
    void textAndBooleanCheckTheirShape() {
        SettingsField url = SettingsField.string("baseUrl", "Base URL", "", "http://localhost:5000");
        accepts(url, StringNode.valueOf("http://libretranslate:5000"));
        assertThat(rejection(url, IntNode.valueOf(5))).isEqualTo("must be text");

        SettingsField flag = SettingsField.bool("verify", "Verify", "", true);
        accepts(flag, BooleanNode.valueOf(false));
        assertThat(rejection(flag, StringNode.valueOf("true"))).isEqualTo("must be true or false");
    }

    @Test
    void anIntegerRefusesAFraction() {
        SettingsField rpm = SettingsField.positiveInt("rpm", "Requests per minute", "", 60);

        accepts(rpm, IntNode.valueOf(120));
        assertThat(rejection(rpm, DoubleNode.valueOf(1.5))).isEqualTo("must be a whole number");
    }

    @Test
    void boundsAreInclusiveAndSayWhichOneFailed() {
        // The reason is the point: "wrong type" tells an admin nothing they can act on.
        SettingsField threshold = SettingsField.decimal("threshold", "Threshold", "", 0.5, 0.0, 1.0);

        accepts(threshold, DoubleNode.valueOf(0.0));
        accepts(threshold, DoubleNode.valueOf(1.0));
        assertThat(rejection(threshold, DoubleNode.valueOf(-0.1))).isEqualTo("must be at least 0");
        assertThat(rejection(threshold, DoubleNode.valueOf(1.1))).isEqualTo("must be at most 1");
    }

    @Test
    void positiveFactoriesAreJustBounds() {
        // Not a type of their own: a field wanting 0..100 needs min/max anyway, and two mechanisms for one
        // idea means a renderer that has to know both.
        assertThat(SettingsField.positiveInt("rpm", "R", "", 60).min()).isEqualTo(1.0);
        assertThat(SettingsField.positiveDecimal("t", "T", "", 0.2).min()).isEqualTo(0.0);
        assertThat(rejection(SettingsField.positiveInt("rpm", "R", "", 60), IntNode.valueOf(0)))
                .isEqualTo("must be at least 1");
    }

    @Test
    void aSelectListsWhatItWouldHaveAccepted() {
        SettingsField format = SettingsField.select("format", "Format", "", "text",
                List.of(new Option("text", "Plain text"), new Option("html", "HTML")));

        accepts(format, StringNode.valueOf("html"));
        assertThat(rejection(format, StringNode.valueOf("markdown"))).isEqualTo("must be one of: text, html");
        assertThat(rejection(format, IntNode.valueOf(1))).isEqualTo("must be one of: text, html");
    }

    @Test
    void aValueCannotBeSetForSomethingThatHoldsNone() {
        assertThat(rejection(SettingsField.envSecret("apiKey", "API key", "", "API_KEY"),
                StringNode.valueOf("sk-live-1234")))
                .contains("supplied through the environment");
        assertThat(rejection(SettingsField.info("note", "Note", "read this"), StringNode.valueOf("x")))
                .isEqualTo("is not a setting");
    }

    @Test
    void aStoredSecretIsJustTextToThisLayer() {
        // The masking, the encryption and the never-echoing happen elsewhere; a value check that treated it
        // specially would only be a second place to get the same rule slightly different.
        accepts(SettingsField.secret("apiKey", "API key", ""), StringNode.valueOf("sk-live-1234"));
        assertThat(rejection(SettingsField.secret("apiKey", "API key", ""), IntNode.valueOf(1)))
                .isEqualTo("must be text");
    }

    @Test
    void aManifestReportsEveryBadFieldAtOnce() {
        // One round trip, every row annotated: an admin fixing a form one rejection at a time is a form that
        // wastes their afternoon.
        SettingsManifest manifest = SettingsManifest.of(
                SettingsField.requiredString("baseUrl", "Base URL", "", null),
                SettingsField.positiveInt("rpm", "Requests per minute", "", 60));

        List<SettingsManifest.FieldError> problems = manifest.validate(Map.of(
                "baseUrl", IntNode.valueOf(5),
                "rpm", IntNode.valueOf(0),
                "nonsense", StringNode.valueOf("x")));

        assertThat(problems).extracting(SettingsManifest.FieldError::key)
                .containsExactlyInAnyOrder("baseUrl", "rpm", "nonsense");
        assertThat(problems).extracting(SettingsManifest.FieldError::reason)
                .anyMatch(reason -> reason.contains("not a setting this provider declares"));
    }

    @Test
    void aRejectionNeverEchoesTheSubmittedValue() {
        // A SECRET's value in an error body would land in an access log and the admin log viewer.
        String reason = rejection(SettingsField.secret("apiKey", "API key", ""),
                IntNode.valueOf(12345));

        assertThat(reason).doesNotContain("12345");
    }

    @Test
    void storedFieldsExcludeTheOnesThatHoldNothing() {
        SettingsManifest manifest = SettingsManifest.of(
                SettingsField.string("baseUrl", "Base URL", "", "http://localhost:5000"),
                SettingsField.envSecret("apiKey", "API key", "", "API_KEY"),
                SettingsField.info("note", "Note", "read this"));

        assertThat(manifest.storedFields()).extracting(SettingsField::key).containsExactly("baseUrl");
        assertThat(manifest.defaults()).containsOnlyKeys("baseUrl");
    }
}
