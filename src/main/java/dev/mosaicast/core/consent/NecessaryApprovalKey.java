// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Identifies one approved service: the plugin that declared it and the service within that plugin. */
@Embeddable
public class NecessaryApprovalKey implements Serializable {

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    /** The service's declared {@code id}, or its name when it declares none — {@code id} is optional. */
    @Column(name = "service_key", nullable = false)
    private String serviceKey;

    protected NecessaryApprovalKey() {
        // for JPA
    }

    public NecessaryApprovalKey(String pluginId, String serviceKey) {
        this.pluginId = pluginId;
        this.serviceKey = serviceKey;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof NecessaryApprovalKey key
                && Objects.equals(pluginId, key.pluginId)
                && Objects.equals(serviceKey, key.serviceKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, serviceKey);
    }
}
