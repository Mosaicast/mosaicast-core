// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.admin;

import dev.mosaicast.core.external.settings.SettingsField;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The payloads the external-services admin API speaks (ARCHITECTURE §12.7). */
public final class AdminExternalViews {

    private AdminExternalViews() {
    }

    /**
     * One service kind: what is selected, whether it can run, and what is on offer.
     *
     * @param selectedProviderId {@code null} when none is selected
     * @param ready              selected <em>and</em> every required setting satisfied
     * @param missingSettings    which required fields are still unsatisfied; empty when ready
     * @param encryptsSecrets    whether stored credentials are encrypted at rest, so the page can say so
     *                           rather than letting an operator assume it
     */
    public record AdminKindSection(
            String kind,
            String selectedProviderId,
            boolean ready,
            List<String> missingSettings,
            boolean encryptsSecrets,
            List<AdminProvider> providers) {
    }

    /**
     * One provider on offer.
     *
     * <p>Everything after {@code homepage} exists to be read <em>before</em> selecting: choosing a provider
     * decides whose servers this site's text is sent to, and that belongs in front of the admin rather than
     * in a changelog they find afterwards.
     */
    public record AdminProvider(
            String id,
            String name,
            String description,
            String homepage,
            String privacyUrl,
            boolean selfHosted,
            boolean paid,
            boolean thirdCountryTransfer,
            List<AdminSettingsField> fields) {
    }

    /**
     * One settings row.
     *
     * @param value   the admin's override, or {@code null}. <strong>Always {@code null} for a credential</strong>
     *                — by construction, not by a redaction branch someone can later break: the mapper has no
     *                path that reads a secret, so there is nothing to forget.
     * @param set     whether a credential actually has a value, wherever it lives. The only thing the API
     *                will say about one.
     * @param envVar  the derived variable name for an env-backed field, so the page can tell the operator
     *                exactly what to export
     */
    public record AdminSettingsField(
            String key,
            String type,
            String label,
            String description,
            JsonNode defaultValue,
            JsonNode value,
            boolean overridden,
            boolean required,
            boolean set,
            Double min,
            Double max,
            List<SettingsField.Option> options,
            String envVar,
            String placeholder) {
    }

    /** The languages-page-style save request: which provider a kind should use. */
    public record SelectProviderRequest(String providerId) {
    }

    /** The outcome of the admin's "Test" button. */
    public record AdminProbeResult(boolean ok, String detail, long millis) {
    }

    /** One rejected field, so a form can annotate every bad row in one round trip. */
    public record FieldError(String key, String reason) {
    }
}
