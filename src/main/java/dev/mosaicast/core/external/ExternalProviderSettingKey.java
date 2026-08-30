// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link ExternalProviderSetting}: the kind, the provider, and the declared field. */
@Embeddable
public class ExternalProviderSettingKey implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "key", nullable = false)
    private String key;

    protected ExternalProviderSettingKey() {
        // for JPA
    }

    public ExternalProviderSettingKey(ExternalServiceKind kind, String providerId, String key) {
        this.kind = kind.id();
        this.providerId = providerId;
        this.key = key;
    }

    public String getKind() {
        return kind;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExternalProviderSettingKey that)) {
            return false;
        }
        return Objects.equals(kind, that.kind)
                && Objects.equals(providerId, that.providerId)
                && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, providerId, key);
    }
}
