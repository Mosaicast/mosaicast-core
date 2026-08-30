// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import dev.mosaicast.core.external.ExternalKindSupport;
import dev.mosaicast.core.external.ExternalProperties;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.cache.ExternalCacheKey;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** What makes two translation requests the same request (ARCHITECTURE §12.7). */
@Component
public class TranslationKindSupport implements ExternalKindSupport<TranslationRequest, TranslationResult> {

    private final ExternalProperties properties;

    public TranslationKindSupport(ExternalProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExternalServiceKind kind() {
        return ExternalServiceKind.TRANSLATION;
    }

    @Override
    public Class<TranslationResult> outputType() {
        return TranslationResult.class;
    }

    /**
     * Identity: source, target, format and the text.
     *
     * <p>Leading {@code v1} is a schema version for this definition. If what makes two requests equal ever
     * changes, bumping it invalidates cleanly instead of colliding with entries written under the old rule
     * — which would surface as a translation that is subtly wrong for reasons nobody can reproduce.
     *
     * <p>The text is hashed rather than embedded: a legal page body is kilobytes and this becomes a primary
     * key.
     */
    @Override
    public String cacheIdentity(TranslationRequest input) {
        return "v1|%s|%s|%s|%s".formatted(input.from(), input.to(), input.format(),
                ExternalCacheKey.sha256(input.text()));
    }

    @Override
    public Duration defaultCacheTtl() {
        return properties.cacheTtlOrDefault();
    }
}
