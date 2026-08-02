// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.consent.ConsentService.ConsentView;
import dev.mosaicast.core.legal.LegalService;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginManifest;
import dev.mosaicast.core.plugin.PluginRegistration;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Risk-point tests for the consent aggregate (ARCHITECTURE §12.5, §13.5). Three rules matter here and none of
 * them is obvious from the types: {@code necessary} is disclosed but never asked about, the visitor-facing
 * payload must not name plugins, and the fingerprint has to be stable against everything except a real change
 * to what a visitor is being told.
 */
@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private PluginLoaderService plugins;

    @Mock
    private LegalService legal;

    @Mock
    private SiteConfigService siteConfig;

    private ConsentService service;

    @BeforeEach
    void setUp() {
        SiteConfig config = mock(SiteConfig.class);
        lenient().when(config.getDefaultLocale()).thenReturn("en");
        lenient().when(siteConfig.get()).thenReturn(config);
        lenient().when(legal.footer("en")).thenReturn(List.of());
        service = new ConsentService(plugins, legal, siteConfig);
    }

    @Test
    void necessaryServicesAreAllowedAndDisclosedButNeverAskedAbout() {
        when(plugins.allActive()).thenReturn(List.of(
                plugin("stats", service("Fixture Analytics", "analytics", "https://plausible.example")),
                plugin("chat", service("Session Keeper", "necessary", "https://necessary.example"))));

        ConsentView view = service.current();

        assertThat(view.categories()).singleElement()
                .satisfies(category -> assertThat(category.id()).isEqualTo("analytics"));
        // It loads whether or not anyone is asked, so the browser has to be allowed to load it.
        assertThat(service.declaredExternalSources())
                .containsExactlyInAnyOrder("https://plausible.example", "https://necessary.example");
        // And an operator still has to be able to see it, which is what the audit is for.
        assertThat(service.audit().services())
                .anySatisfy(declared -> {
                    assertThat(declared.category()).isEqualTo("necessary");
                    assertThat(declared.prompted()).isFalse();
                });
        // A visitor has to be able to see it too. Not a question does not mean not a disclosure: it still
        // puts things on their device, and the shell needs the list for a second reason — it sweeps away
        // everything unaccounted for, and this is what keeps it from mistaking a necessary service's own
        // storage for a stray.
        assertThat(view.necessaryServices()).singleElement()
                .satisfies(declared -> assertThat(declared.name()).isEqualTo("Session Keeper"));
    }

    @Test
    void thePublicViewNamesServicesAndProvidersButNotPlugins() {
        when(plugins.allActive()).thenReturn(List.of(
                plugin("stats", service("Fixture Analytics", "analytics", "https://plausible.example"))));

        ConsentView view = service.current();

        assertThat(view.categories().getFirst().services()).singleElement().satisfies(declared -> {
            assertThat(declared.name()).isEqualTo("Fixture Analytics");
            assertThat(declared.provider()).isEqualTo("Fixture Analytics Ltd.");
        });
        // The record simply has nowhere to put a plugin id — that is the enforcement, and this test is what
        // notices if someone adds one back.
        assertThat(ConsentService.ServiceView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("pluginId");
    }

    @Test
    void whatTheCoreItselfStoresIsAlwaysDisclosed() {
        when(plugins.allActive()).thenReturn(List.of());

        ConsentView view = service.current();

        // No plugin, no question — but the disclosure is unconditional, because a banner-free site still
        // writes a session cookie and a language preference.
        assertThat(view.categories()).isEmpty();
        assertThat(view.essential().storage())
                .extracting(CoreStorageInventory.Item::name)
                .contains("mc.locale", "mc.consent", "mc.progress.*");
        // Listening progress is the one item a visitor can switch off, which is why it is marked.
        assertThat(view.essential().storage())
                .filteredOn(CoreStorageInventory.Item::optional)
                .extracting(CoreStorageInventory.Item::name)
                .containsExactly("mc.progress.*");
    }

    @Test
    void aRefusedCategoryLosesItsOriginsFromThePolicy() {
        when(plugins.allActive()).thenReturn(List.of(
                plugin("stats", service("Fixture Analytics", "analytics", "https://plausible.example")),
                plugin("chat", service("Session Keeper", "necessary", "https://necessary.example"))));

        // Nothing granted — the state before any decision, and after a refusal. `has()` is advisory, so this
        // is the part a plugin cannot ignore: the origin simply is not in the policy.
        assertThat(service.allowedSources(Set.of())).containsExactly("https://necessary.example");

        // Granted: the declared origin comes back.
        assertThat(service.allowedSources(Set.of("analytics")))
                .containsExactlyInAnyOrder("https://plausible.example", "https://necessary.example");

        // A category nobody declared grants nothing — a forged cookie widens no further than the manifest.
        assertThat(service.allowedSources(Set.of("analytics", "made-up")))
                .containsExactlyInAnyOrder("https://plausible.example", "https://necessary.example");
    }

    @Test
    void aSiteWithNothingOptionalNeedsNoPerVisitorPolicy() {
        when(plugins.allActive()).thenReturn(List.of(
                plugin("chat", service("Session Keeper", "necessary", "https://necessary.example"))));

        // Drives whether the response has to carry `Vary: Cookie`: when every declared service is necessary,
        // the policy is identical for everyone and varying would cost cacheability for nothing.
        assertThat(service.hasOptionalSources()).isFalse();

        when(plugins.allActive()).thenReturn(List.of(
                plugin("stats", service("Fixture Analytics", "analytics", "https://plausible.example"))));
        assertThat(service.hasOptionalSources()).isTrue();
    }

    @Test
    void theFingerprintIgnoresOrderButNotContent() {
        PluginRegistration a = plugin("stats", service("Fixture Analytics", "analytics", "https://a.example"));
        PluginRegistration b = plugin("chat", service("Chat Widget", "functional", "https://b.example"));

        when(plugins.allActive()).thenReturn(List.of(a, b));
        String ordered = service.fingerprint();
        when(plugins.allActive()).thenReturn(List.of(b, a));

        // Plugin discovery order is filesystem order; it must not invalidate a valid answer.
        assertThat(service.fingerprint()).isEqualTo(ordered);

        // A changed provider name changes what the visitor is told, so the old answer no longer covers it.
        when(plugins.allActive()).thenReturn(List.of(a,
                plugin("chat", new PluginManifest.Service("id", "Chat Widget", "Someone Else Ltd.",
                        "functional", "https://b.example/privacy", List.of("https://b.example"), false,
                        List.of()))));
        assertThat(service.fingerprint()).isNotEqualTo(ordered);
    }

    @Test
    void aStorageLifetimeChangeAloneChangesTheFingerprint() {
        PluginManifest.Service before = new PluginManifest.Service("id", "Fixture Analytics",
                "Fixture Analytics Ltd.", "analytics", "https://a.example/privacy",
                List.of("https://a.example"), false,
                List.of(new PluginManifest.StorageItem("fixture_id", "cookie", "counts a visit", "24 hours")));
        PluginManifest.Service after = new PluginManifest.Service("id", "Fixture Analytics",
                "Fixture Analytics Ltd.", "analytics", "https://a.example/privacy",
                List.of("https://a.example"), false,
                List.of(new PluginManifest.StorageItem("fixture_id", "cookie", "counts a visit", "12 months")));

        when(plugins.allActive()).thenReturn(List.of(plugin("stats", before)));
        String initial = service.fingerprint();
        when(plugins.allActive()).thenReturn(List.of(plugin("stats", after)));

        // "24 hours" and "12 months" are different disclosures, and consent to one is not consent to the
        // other — a lifetime buried three levels down still has to reach the visitor.
        assertThat(service.fingerprint()).isNotEqualTo(initial);
    }

    private static PluginManifest.Service service(String name, String category, String host) {
        return new PluginManifest.Service("id", name, name + " Ltd.", category, host + "/privacy",
                List.of(host), false, List.of());
    }

    private static PluginRegistration plugin(String id, PluginManifest.Service... services) {
        PluginManifest manifest = new PluginManifest(id, "1.0.0", "0.4.0", id, null, null, List.of(),
                PluginManifest.STORAGE_DOC, null, new PluginManifest.Consent(List.of(services)));
        return PluginRegistration.loaded(manifest, Path.of("/tmp/" + id));
    }
}
