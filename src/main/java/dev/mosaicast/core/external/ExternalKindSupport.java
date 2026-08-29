// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import java.time.Duration;

/**
 * Per-kind wiring: the generics the enum cannot carry, plus what makes two requests the same request
 * (ARCHITECTURE §12.7).
 *
 * <p>Adding a kind is one enum constant, one of these beans, one provider interface, an input and an output
 * record, and two i18n keys. Nothing in the registry, the settings model, the pipeline, the cache, the admin
 * controller or the frontend changes — which holds precisely because the kind-agnostic layer never names
 * {@link ExternalServiceKind#TRANSLATION}.
 *
 * @param <I> the kind's generalized input
 * @param <O> the kind's generalized output
 */
public interface ExternalKindSupport<I, O> {

    ExternalServiceKind kind();

    /** The output type, for deserializing a cache hit back into something typed. */
    Class<O> outputType();

    /**
     * The canonical, provider-independent identity of one request.
     *
     * <p>The gateway combines this with the provider id and a fingerprint of the non-secret settings; a kind
     * only has to say what makes two requests equal. Start the string with a version marker so a later change
     * to that definition invalidates cleanly instead of colliding with entries written under the old one.
     *
     * @return a stable canonical string, or {@code null} when this input must never be cached
     */
    String cacheIdentity(I input);

    /** How long a cached result stays good, or {@code null} for "until something purges it". */
    Duration defaultCacheTtl();
}
