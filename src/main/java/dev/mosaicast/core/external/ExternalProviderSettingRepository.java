// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Storage for admin-set provider settings. */
public interface ExternalProviderSettingRepository
        extends JpaRepository<ExternalProviderSetting, ExternalProviderSettingKey> {

    List<ExternalProviderSetting> findByIdKindAndIdProviderId(String kind, String providerId);
}
