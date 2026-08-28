// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import dev.mosaicast.plugin.api.LocaleInfo;
import dev.mosaicast.plugin.api.Locales;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The host's answer to {@code ctx.locales()} (ARCHITECTURE §12.7, SDK 0.10.0).
 *
 * <p>A thin adapter over {@link LocaleRegistry}, and thin on purpose: the plugin contract's
 * {@link LocaleInfo} is a different type from core's own, and mapping at the boundary is what lets core's
 * record grow catalog origin and translation debt — an operator's concern — without publishing either to
 * every plugin.
 *
 * <p><strong>Reads through, never caches.</strong> A plugin holds this handle for its whole life, and an
 * admin can change the language policy underneath it at any time; a snapshot taken at {@code register()}
 * would answer with whatever was true at boot for as long as the process lived.
 */
@Component
public class PluginLocales implements Locales {

    private final LocaleRegistry registry;

    public PluginLocales(LocaleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<LocaleInfo> available() {
        return registry.uiLocales().stream().map(PluginLocales::toApi).toList();
    }

    @Override
    public List<LocaleInfo> contentLocales() {
        return registry.contentLocales().stream().map(PluginLocales::toApi).toList();
    }

    @Override
    public String defaultLocale() {
        return registry.defaultLocale();
    }

    @Override
    public boolean isContentLocale(String code) {
        return code != null && !code.isBlank() && registry.isContentLocale(code);
    }

    private static LocaleInfo toApi(dev.mosaicast.core.i18n.LocaleInfo info) {
        return new LocaleInfo(info.code(), info.nativeName(), info.isDefault());
    }
}
