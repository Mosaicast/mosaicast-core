// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import dev.mosaicast.core.external.error.ExternalProviderException;
import dev.mosaicast.core.external.settings.ProviderConfig;

/**
 * One third-party service implementation (ARCHITECTURE §12.7).
 *
 * <p><strong>A compile-time Spring bean, not a PF4J plugin.</strong> Providers ship with the host, are
 * reviewed with it, hold operator credentials and speak to paid APIs — which is precisely the set of
 * capabilities the plugin sandbox exists to withhold. Adding one is a pull request against core, and that is
 * the point: an operator installing a plugin should never thereby be trusting it with a billing relationship.
 *
 * <p><strong>An implementation does exactly one thing:</strong> shape the request, speak HTTP, shape the
 * response. Caching, bounding, rate limiting, budgets and timeouts belong to the pipeline that wraps it —
 * uniformly, for every provider of every kind. A provider that retried internally would defeat the rate
 * limiter; one that cached internally would defeat a purge.
 *
 * @param <I> the kind's generalized input
 * @param <O> the kind's generalized output
 */
public interface ExternalProvider<I, O> {

    /** What this is and what it needs configured. */
    ProviderDescriptor descriptor();

    /**
     * Performs the call.
     *
     * @param input  the request, already normalized by the kind
     * @param config the admin's settings, resolved
     * @return the result
     * @throws ExternalProviderException on any upstream failure. <strong>The message must never carry the
     *         upstream response body</strong> — it can contain the credential that was just sent, and for a
     *         private endpoint it is a read-back oracle. Log the detail, return a sanitized message.
     */
    O call(I input, ProviderConfig config) throws ExternalProviderException;

    /**
     * How much of {@link ProviderDescriptor#unit()} this input will consume, known before the call.
     *
     * <p>Used to reserve budget before spending it. An estimate, corrected afterwards by
     * {@link #actualUnits}, because checking headroom and then spending it in two steps is the classic
     * overspend under concurrency.
     */
    default long estimateUnits(I input) {
        return 1;
    }

    /** What it actually consumed, if the response says. Defaults to the estimate. */
    default long actualUnits(I input, O output) {
        return estimateUnits(input);
    }

    /**
     * A cheap, fixed round trip for the admin's "Test" button.
     *
     * <p>Must not depend on caller input: it exists to answer "is this configured correctly", and a probe
     * that translated user text would be a way to spend budget through a button labelled *test*.
     */
    ProbeResult probe(ProviderConfig config);

    /**
     * The outcome of a probe.
     *
     * @param detail a short, fixed-vocabulary explanation — never the upstream body, for the reason above
     */
    record ProbeResult(boolean ok, String detail) {

        public static ProbeResult ok(String detail) {
            return new ProbeResult(true, detail);
        }

        public static ProbeResult failed(String detail) {
            return new ProbeResult(false, detail);
        }
    }
}
