// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import java.util.Optional;

/**
 * A provider's resolved settings at the moment of a call (ARCHITECTURE §12.7).
 *
 * <p>Overrides the admin set, layered over the descriptor's defaults, with credentials resolved from wherever
 * the field said they live. A provider reads values through here and never touches storage or the environment
 * itself — which is what keeps a provider a pure "shape the request, speak HTTP, shape the response".
 */
public interface ProviderConfig {

    /**
     * A text setting.
     *
     * @throws IllegalStateException if the field is required and neither set nor defaulted — a provider
     *                               should not have been invoked in that state, so this is a bug in the
     *                               readiness check rather than something to handle
     */
    String string(String key);

    Optional<String> optionalString(String key);

    int integer(String key);

    double decimal(String key);

    boolean bool(String key);

    /**
     * A credential, from the environment or from storage depending on how the field was declared.
     *
     * <p>Empty when unset. A provider must degrade rather than throw: an admin can remove a variable between
     * the readiness check and the call.
     */
    Optional<String> secret(String key);

    /**
     * A stable hash of the non-secret settings.
     *
     * <p>Part of the cache key: pointing a provider at a different base URL is pointing it at a different
     * model on a different machine, and results from the old one would be quietly wrong.
     *
     * <p><strong>Secrets are excluded.</strong> Rotating an API key must not throw away work that was paid
     * for — the key says who is asking, not what the answer is.
     */
    String fingerprint();
}
