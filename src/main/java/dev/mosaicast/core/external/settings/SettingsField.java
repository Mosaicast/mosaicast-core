// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

/**
 * One setting a provider needs from the admin (ARCHITECTURE §12.7).
 *
 * <p>The same idea as a plugin manifest's {@code config} field, with the gaps that one has filled in:
 * a label and a description (the plugin form renders the raw field key today), bounds, an option list, a
 * secret type, and a rejection that says <em>which</em> constraint failed. That last part is why
 * {@link #accepts(JsonNode)} returns a {@link Result} rather than a boolean —
 * {@code PluginManifest.ConfigField.accepts} returns a bare {@code true}/{@code false} and forces its
 * controller to rebuild an English message out of the type name, which can only ever say "wrong type".
 *
 * @param key          stable; the stored key and the i18n key suffix
 * @param type         what the value is
 * @param label        English fallback label; the UI prefers a catalog key and falls back to this
 * @param description  English fallback help text; may be long for {@link SettingsFieldType#INFO}
 * @param defaultValue the value used when the admin sets none; {@code null} for secrets and info
 * @param required     whether the provider cannot work without it
 * @param min          lower bound for {@code INTEGER}/{@code DECIMAL}, or {@code null}
 * @param max          upper bound for {@code INTEGER}/{@code DECIMAL}, or {@code null}
 * @param options      the allowed values for {@code SELECT}; empty otherwise
 * @param envVarSuffix the <em>suffix</em> of the environment variable, for {@code ENV_SECRET}/{@code INFO} —
 *                     never a full name; see {@link EnvProbe} for why the host derives the rest
 * @param placeholder  an example value for the input, or {@code null}
 */
public record SettingsField(
        String key,
        SettingsFieldType type,
        String label,
        String description,
        JsonNode defaultValue,
        boolean required,
        Double min,
        Double max,
        List<Option> options,
        String envVarSuffix,
        String placeholder) {

    /** One choice in a {@link SettingsFieldType#SELECT}. */
    public record Option(String value, String label) {
    }

    /** The outcome of checking a value. */
    public sealed interface Result {

        /** The value is acceptable. */
        record Ok() implements Result {
        }

        /**
         * The value is not acceptable.
         *
         * @param reason English, and names the constraint that failed — "must be at least 1", not "wrong
         *               type". The admin form puts this on the row, so it has to say what to do about it.
         */
        record Rejected(String reason) implements Result {
        }
    }

    /** A key that can be a database key, a JSON property and an i18n key suffix without escaping. */
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{0,39}$");

    /** Whether a key is one this system will store and render. Checked at startup, not per request. */
    public static boolean isLegalKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public SettingsField {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /**
     * Whether {@code value} is a legal setting for this field.
     *
     * <p>A JSON null always passes: it is how an admin clears an override and goes back to the default,
     * which is the same convention {@code plugin_config} uses.
     *
     * @param value the submitted value
     * @return {@link Result.Ok} or a {@link Result.Rejected} naming the constraint
     */
    public Result accepts(JsonNode value) {
        if (value == null || value.isNull()) {
            return new Result.Ok();
        }
        return switch (type) {
            case STRING, SECRET -> value.isString()
                    ? new Result.Ok()
                    : new Result.Rejected("must be text");
            case BOOLEAN -> value.isBoolean()
                    ? new Result.Ok()
                    : new Result.Rejected("must be true or false");
            case INTEGER -> {
                if (!value.isNumber() || value.doubleValue() != Math.floor(value.doubleValue())) {
                    yield new Result.Rejected("must be a whole number");
                }
                yield withinBounds(value.doubleValue());
            }
            case DECIMAL -> value.isNumber()
                    ? withinBounds(value.doubleValue())
                    : new Result.Rejected("must be a number");
            case SELECT -> {
                if (!value.isString()) {
                    yield new Result.Rejected("must be one of: " + optionValues());
                }
                yield options.stream().anyMatch(option -> option.value().equals(value.stringValue()))
                        ? new Result.Ok()
                        : new Result.Rejected("must be one of: " + optionValues());
            }
            // Neither holds a stored value, so submitting one is a client bug rather than a bad value.
            case ENV_SECRET -> new Result.Rejected(
                    "is supplied through the environment and cannot be set here");
            case INFO -> new Result.Rejected("is not a setting");
        };
    }

    private Result withinBounds(double number) {
        if (min != null && number < min) {
            return new Result.Rejected("must be at least " + trim(min));
        }
        if (max != null && number > max) {
            return new Result.Rejected("must be at most " + trim(max));
        }
        return new Result.Ok();
    }

    private String optionValues() {
        return String.join(", ", options.stream().map(Option::value).toList());
    }

    /** Whole numbers print without a trailing {@code .0}, because "at least 1.0" reads like a decimal field. */
    private static String trim(double bound) {
        return bound == Math.floor(bound) && !Double.isInfinite(bound)
                ? String.valueOf((long) bound)
                : String.valueOf(bound);
    }

    // ---- factories ----

    public static SettingsField string(String key, String label, String description, String defaultValue) {
        return new SettingsField(key, SettingsFieldType.STRING, label, description,
                defaultValue == null ? null : StringNode.valueOf(defaultValue),
                false, null, null, List.of(), null, null);
    }

    public static SettingsField requiredString(String key, String label, String description, String placeholder) {
        return new SettingsField(key, SettingsFieldType.STRING, label, description, null,
                true, null, null, List.of(), null, placeholder);
    }

    /** A credential the admin types, stored in the database. Prefer {@link #envSecret}. */
    public static SettingsField secret(String key, String label, String description) {
        return secret(key, label, description, true);
    }

    /**
     * A credential the admin types, which the provider may or may not need.
     *
     * <p><strong>Optional is a real case, not a convenience.</strong> A self-hosted LibreTranslate runs with
     * {@code keyRequired: false} by default and needs no credential at all; the same image behind a public
     * URL usually enforces one. A provider that declared its key mandatory would be unusable on the first
     * instance, and one that declared it absent would be unusable on the second. The field is the same
     * either way — what changes is whether the readiness check refuses without it.
     */
    public static SettingsField secret(String key, String label, String description, boolean required) {
        return new SettingsField(key, SettingsFieldType.SECRET, label, description, null,
                required, null, null, List.of(), null, null);
    }

    /** A credential read from a host-derived environment variable. The recommended shape. */
    public static SettingsField envSecret(String key, String label, String description, String envVarSuffix) {
        return envSecret(key, label, description, envVarSuffix, true);
    }

    /** An environment-supplied credential the provider may or may not need. See {@link #secret}. */
    public static SettingsField envSecret(String key, String label, String description,
                                          String envVarSuffix, boolean required) {
        return new SettingsField(key, SettingsFieldType.ENV_SECRET, label, description, null,
                required, null, null, List.of(), envVarSuffix, null);
    }

    public static SettingsField integer(String key, String label, String description,
                                        int defaultValue, Integer min, Integer max) {
        return new SettingsField(key, SettingsFieldType.INTEGER, label, description,
                IntNode.valueOf(defaultValue), false,
                min == null ? null : min.doubleValue(), max == null ? null : max.doubleValue(),
                List.of(), null, null);
    }

    /** A whole number of at least 1 — a rate limit, a retry count. */
    public static SettingsField positiveInt(String key, String label, String description, int defaultValue) {
        return integer(key, label, description, defaultValue, 1, null);
    }

    public static SettingsField decimal(String key, String label, String description,
                                        double defaultValue, Double min, Double max) {
        return new SettingsField(key, SettingsFieldType.DECIMAL, label, description,
                DoubleNode.valueOf(defaultValue), false, min, max, List.of(), null, null);
    }

    /** A fractional number of at least 0 — a threshold, a temperature. */
    public static SettingsField positiveDecimal(String key, String label, String description,
                                                double defaultValue) {
        return decimal(key, label, description, defaultValue, 0.0, null);
    }

    public static SettingsField bool(String key, String label, String description, boolean defaultValue) {
        return new SettingsField(key, SettingsFieldType.BOOLEAN, label, description,
                BooleanNode.valueOf(defaultValue), false, null, null, List.of(), null, null);
    }

    public static SettingsField select(String key, String label, String description,
                                       String defaultValue, List<Option> options) {
        return new SettingsField(key, SettingsFieldType.SELECT, label, description,
                defaultValue == null ? null : StringNode.valueOf(defaultValue),
                false, null, null, options, null, null);
    }

    /** Prose the admin should read, with no value attached. */
    public static SettingsField info(String key, String label, String description) {
        return new SettingsField(key, SettingsFieldType.INFO, label, description, null,
                false, null, null, List.of(), null, null);
    }

    /** Prose plus a live check on a host-derived environment variable that is not a credential. */
    public static SettingsField envInfo(String key, String label, String description, String envVarSuffix) {
        return new SettingsField(key, SettingsFieldType.INFO, label, description, null,
                false, null, null, List.of(), envVarSuffix, null);
    }
}
