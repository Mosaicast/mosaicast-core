// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.legal.LegalService;
import dev.mosaicast.core.legal.LegalViews.FooterEntry;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginManifest;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.core.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The platform consent service (ARCHITECTURE §12.5).
 *
 * <p>The core stores only what the requested service needs — session, CSRF token, language, cached branding,
 * the consent decision itself — so <strong>it runs banner-free</strong>. A visitor is asked something only
 * because a plugin declared a third-party service in its manifest. What that plugin declares is both the
 * notice and the CSP permission: an origin nobody declared stays blocked even with consent granted.
 *
 * <p><strong>This class speaks to visitors, so it does not speak about plugins.</strong> The public view
 * carries no plugin ids and no slugs: a visitor decides about <em>services</em> operated by named
 * <em>providers</em> that store named things on their device. Plugin attribution is real and useful — to an
 * operator — so it lives in {@link #audit()}, which is admin-only.
 *
 * <p>Only <em>active</em> plugins count: switching one off removes its services, and with them the question.
 */
@Service
public class ConsentService {

    /**
     * Categories the host has a translated label for. {@code necessary} is never prompted for — it is what the
     * core itself uses. Anything else a manifest declares passes through as a plugin-declared category, so a
     * plugin is not limited to this vocabulary; the shell simply shows the raw name.
     */
    public static final String CATEGORY_NECESSARY = "necessary";
    public static final String CATEGORY_FUNCTIONAL = "functional";
    public static final String CATEGORY_ANALYTICS = "analytics";

    /**
     * Where a plugin's <em>unapproved</em> {@code necessary} claim lands until an admin rules on it.
     *
     * <p>A distinct id rather than reusing {@code necessary}, because the two are different questions. One
     * says "this loads whatever you choose, because the site cannot work without it"; the other says "a
     * plugin asserts that, and nobody has checked". Presenting the second under the first's label would put
     * the plugin's own words in the operator's mouth on the one surface that exists to be truthful.
     */
    public static final String CATEGORY_NECESSARY_UNAPPROVED = "unreviewed";

    public static final Set<String> KNOWN_CATEGORIES =
            Set.of(CATEGORY_NECESSARY, CATEGORY_FUNCTIONAL, CATEGORY_ANALYTICS,
                    CATEGORY_NECESSARY_UNAPPROVED);

    private final PluginLoaderService plugins;
    private final LegalService legal;
    private final SiteConfigService siteConfig;
    private final NecessaryApprovalService approvals;

    public ConsentService(PluginLoaderService plugins, LegalService legal, SiteConfigService siteConfig,
                          NecessaryApprovalService approvals) {
        this.plugins = plugins;
        this.legal = legal;
        this.siteConfig = siteConfig;
        this.approvals = approvals;
    }

    /**
     * What the shell needs to ask, to disclose, and to know whether an old answer still applies.
     *
     * @param fingerprint a stable digest of everything declared; when it changes, the declared set changed and
     *                    the shell asks again instead of letting a newly installed service inherit an answer
     *                    given before it existed
     * @param categories        the decisions offered, {@code necessary} excluded; empty means <em>no banner</em>
     * @param essential         what the core stores unconditionally — disclosed, never asked about
     * @param necessaryServices services a plugin declared as {@code necessary}: never a question, but still
     *                          storing things on a device, so still a disclosure. They are listed separately
     *                          rather than folded into {@link #categories()} precisely because listing them
     *                          there would imply a toggle that does not exist
     * @param privacySlug       the legal page marked {@code privacy} (§12.6) to link, or {@code null}
     */
    public record ConsentView(String fingerprint, List<CategoryView> categories, EssentialView essential,
                              List<ServiceView> necessaryServices, String privacySlug) {
    }

    /**
     * One decision the visitor makes, and every service it covers.
     *
     * <p>The category is the unit of decision even though the declaration is per service: two services sharing
     * a category are granted or refused together, which is exactly what {@code ctx.consent.has(category)}
     * gates on. The services are listed so the notice can name who does what — not so they can be toggled
     * individually.
     *
     * @param id       the category name, as declared
     * @param known    whether the shell has a translated label for it
     * @param affectsPolicy whether granting or refusing this changes the CSP the server sends — i.e. whether
     *                 any service under it declares an origin at all. The shell needs it because enforcement
     *                 lives in a response header that cannot be changed after delivery, so a decision that
     *                 moves the policy has to be applied by reloading, and one that does not must not be:
     *                 reloading on a no-op would throw away the visitor's place in an episode for nothing.
     *                 A boolean, not the hosts themselves — a visitor decides about services and providers,
     *                 and the origin list stays in the admin audit where the class note says it belongs
     * @param services the services this decision covers
     */
    public record CategoryView(String id, boolean known, boolean affectsPolicy, List<ServiceView> services) {
    }

    /**
     * One third-party service, as a visitor reads about it. No plugin id — see the class note.
     *
     * @param name                 the service as a visitor would recognise it
     * @param provider             the company operating it
     * @param privacyUrl           the provider's own privacy policy
     * @param thirdCountryTransfer whether personal data leaves the EU/EEA
     * @param storage              what it puts on the device, with purpose and lifetime
     */
    public record ServiceView(String name, String provider, String privacyUrl, boolean thirdCountryTransfer,
                              List<StorageView> storage) {
    }

    /** One item a service stores; the text is the plugin author's, verbatim from the manifest. */
    public record StorageView(String name, String type, String purpose, String duration) {
    }

    /**
     * Core's own storage, disclosed but never gated.
     *
     * <p>Its purposes and durations are <strong>i18n keys</strong> rather than sentences, because the
     * disclosure must read correctly in every UI locale and only the shell knows the active one. Plugin
     * storage cannot work that way — its text comes from a manifest written by a plugin author — which is why
     * the two lists have different shapes.
     */
    public record EssentialView(List<CoreStorageInventory.Item> storage) {
    }

    /** The admin audit (§12.5): the same declarations, attributed, plus what the CSP therefore allows. */
    public record AuditView(String fingerprint, List<AuditService> services, List<String> csp) {
    }

    /**
     * One declared service with the plugin that declared it.
     *
     * @param prompted whether a visitor is actually asked about it — {@code false} only for an
     *                 <em>approved</em> {@code necessary} claim, which loads unconditionally and is the answer
     *                 to "why does this origin appear in the CSP when nobody was asked about it?"
     * @param claimsNecessary whether the plugin declared {@code "category": "necessary"} for it at all. Kept
     *                 separate from {@code category} so the admin surface can show a pending claim as a claim,
     *                 rather than the operator discovering it by noticing an origin they never approved
     * @param necessaryApproved whether an admin has approved that claim <em>as it currently stands</em>. A
     *                 plugin update that adds a host or a cookie drops this back to {@code false} and the
     *                 service is prompted again until someone looks — an approval covers a claim, not a plugin
     */
    public record AuditService(String pluginId, String serviceId, String name, String provider, String category,
                               String privacyUrl, List<String> hosts, boolean thirdCountryTransfer,
                               List<StorageView> storage, boolean prompted,
                               boolean claimsNecessary, boolean necessaryApproved) {
    }

    /** The public consent payload. */
    public ConsentView current() {
        Map<String, List<ServiceView>> byCategory = new LinkedHashMap<>();
        Set<String> withHosts = new LinkedHashSet<>();
        List<ServiceView> necessary = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            for (PluginManifest.Service service : declaredServices(registration)) {
                String category = effectiveCategory(registration.id(), service);
                if (category == null) {
                    continue;
                }
                if (CATEGORY_NECESSARY.equals(category)) {
                    // Never a question: it loads either way. It is still a disclosure, though, and the shell
                    // needs the list for a second reason — what it stores is legitimately on the device, so a
                    // sweep of everything unaccounted for must not mistake it for a stray.
                    //
                    // Only an *approved* claim reaches here; an unapproved one came back as a prompted
                    // category and falls through to the toggle list below.
                    necessary.add(view(service));
                    continue;
                }
                byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(view(service));
                if (service.hostsOrEmpty().stream().anyMatch(host -> host != null && !host.isBlank())) {
                    withHosts.add(category);
                }
            }
        }

        List<CategoryView> categories = byCategory.entrySet().stream()
                .map(entry -> new CategoryView(entry.getKey(), KNOWN_CATEGORIES.contains(entry.getKey()),
                        withHosts.contains(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();

        return new ConsentView(fingerprint(), categories,
                new EssentialView(CoreStorageInventory.items()), List.copyOf(necessary), privacySlug());
    }

    /** The admin-only view: everything above, plus who declared it and what the CSP therefore allows. */
    public AuditView audit() {
        List<AuditService> services = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            for (PluginManifest.Service service : declaredServices(registration)) {
                String declared = normalize(service.category());
                String effective = effectiveCategory(registration.id(), service);
                boolean claimsNecessary = CATEGORY_NECESSARY.equals(declared);
                services.add(new AuditService(registration.id(),
                        NecessaryApprovalService.serviceKey(service), service.name(),
                        service.provider(), declared, service.privacyUrl(), service.hostsOrEmpty(),
                        service.transfersToThirdCountry(), storageViews(service),
                        effective != null && !CATEGORY_NECESSARY.equals(effective),
                        claimsNecessary,
                        claimsNecessary && CATEGORY_NECESSARY.equals(effective)));
            }
        }
        return new AuditView(fingerprint(), services, List.copyOf(declaredExternalSources()));
    }

    /**
     * Every distinct third-party origin active plugins declared — the whole allow-list, ignoring what any
     * particular visitor chose. Used by the admin audit; the CSP uses {@link #allowedSources(Set)} instead.
     */
    public Set<String> declaredExternalSources() {
        return hosts(category -> true);
    }

    /**
     * The origins a visitor's CSP may be widened by, given the categories they granted
     * ({@link dev.mosaicast.core.config.PluginCspHeaderWriter}).
     *
     * <p>This is what turns a refusal from a promise into a rule. {@code ctx.consent.has()} is advisory —
     * a plugin can simply not call it, and nothing in a shared JavaScript realm can force it to. What the
     * browser refuses to connect to is not advisory, so a category the visitor declined takes its origins
     * out of the policy and the request fails at the network layer instead of relying on plugin manners.
     *
     * <p>{@code necessary} services are always included: they are not offered as a choice, so there is no
     * decision that could remove them. A category nobody granted contributes nothing, which is also the
     * state before any decision at all — deny by default, exactly like {@code has()}.
     *
     * @param grantedCategories the categories this visitor granted; empty means nothing optional loads
     */
    public Set<String> allowedSources(Set<String> grantedCategories) {
        return hosts(category -> CATEGORY_NECESSARY.equals(category) || grantedCategories.contains(category));
    }

    /**
     * Whether any plugin is claiming {@code necessary} without an admin having ruled on it.
     *
     * <p>Drives the admin nudge: an unapproved claim means visitors are being asked about something the
     * plugin believes needs no asking, which the operator should settle one way or the other.
     */
    public boolean hasPendingNecessaryClaims() {
        for (PluginRegistration registration : plugins.allActive()) {
            for (PluginManifest.Service service : declaredServices(registration)) {
                if (CATEGORY_NECESSARY.equals(normalize(service.category()))
                        && !approvals.isApproved(registration.id(), service)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Approves a plugin's {@code necessary} claim as it currently reads. 404 if there is no such service. */
    public void approveNecessary(String pluginId, String serviceKey) {
        approvals.approve(pluginId, serviceOf(pluginId, serviceKey));
    }

    /** Withdraws that approval. Idempotent, and does not require the service to still exist. */
    public void revokeNecessary(String pluginId, String serviceKey) {
        approvals.revoke(pluginId, serviceKey);
    }

    private PluginManifest.Service serviceOf(String pluginId, String serviceKey) {
        return plugins.allActive().stream()
                .filter(registration -> registration.id().equals(pluginId))
                .flatMap(registration -> declaredServices(registration).stream())
                .filter(service -> NecessaryApprovalService.serviceKey(service).equals(serviceKey))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "No declared service '%s' on plugin '%s'".formatted(serviceKey, pluginId)));
    }

    /** Whether any declared service is gated at all — i.e. whether the policy varies between visitors. */
    public boolean hasOptionalSources() {
        return !declaredExternalSources().equals(allowedSources(Set.of()));
    }

    private Set<String> hosts(java.util.function.Predicate<String> categoryAllowed) {
        Set<String> hosts = new LinkedHashSet<>();
        for (PluginRegistration registration : plugins.allActive()) {
            for (PluginManifest.Service service : declaredServices(registration)) {
                // The effective category, so an unapproved `necessary` claim is subject to the visitor's
                // decision like anything else rather than being waved through by the predicate above.
                String category = effectiveCategory(registration.id(), service);
                if (category == null || !categoryAllowed.test(category)) {
                    continue;
                }
                service.hostsOrEmpty().stream()
                        .filter(host -> host != null && !host.isBlank())
                        .map(String::trim)
                        .forEach(hosts::add);
            }
        }
        return hosts;
    }

    /**
     * A digest of every declaration that reaches a visitor, so the shell can tell "already answered" from
     * "answered a different question".
     *
     * <p>Consent given for two services is not consent for a third that a later install added — and until
     * now the shell had no way to notice, because a stored decision was simply a decision, whatever it had
     * been about. Hashing the declared set turns that into something the client can compare against what it
     * stored. Ordering is normalised, so restarting the host or reordering plugins does not invalidate a
     * valid answer; everything a visitor would read is included, so changing a provider's name or a cookie's
     * lifetime does.
     */
    public String fingerprint() {
        List<String> lines = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            for (PluginManifest.Service service : declaredServices(registration)) {
                // Skip exactly what current() skips.
                //
                // These two used to disagree: current() drops a service whose category is absent or blank,
                // while this appended the literal "null" for it. So a change no visitor could possibly see
                // moved the digest — every stored answer stopped matching, granted categories fell out of the
                // CSP, allowed storage was purged, and the banner re-asked a question already answered. What
                // is hashed has to be what is shown, which is also why the *effective* category goes in:
                // approving a necessary claim moves a service out of the toggle list, and a visitor looking at
                // a different set of questions is being asked something new.
                String category = effectiveCategory(registration.id(), service);
                if (category == null) {
                    continue;
                }
                StringBuilder line = new StringBuilder()
                        .append(category).append('')
                        .append(service.name()).append('')
                        .append(service.provider()).append('')
                        .append(service.privacyUrl()).append('')
                        .append(service.transfersToThirdCountry()).append('')
                        .append(service.hostsOrEmpty().stream().sorted().toList());
                for (PluginManifest.StorageItem item : service.storageOrEmpty()) {
                    line.append('').append(item.name()).append('').append(item.type())
                            .append('').append(item.purpose()).append('').append(item.duration());
                }
                lines.add(line.toString());
            }
        }
        lines.sort(null);
        return digest(String.join("", lines));
    }

    /**
     * The category a service is actually treated as, which is not always the one it declared.
     *
     * <p>{@code necessary} is the only category that skips the visitor entirely — it is never offered, never
     * refusable, and its origins go into every visitor's CSP. That made it the one string worth lying about,
     * and nothing checked: {@code validateConsent} only requires a category to be non-blank, so a tracker
     * declaring itself necessary loaded for everyone and could not be refused.
     *
     * <p>So the claim is a proposal. Approved by an admin, it behaves as before. Unapproved, it is handled as
     * an ordinary prompted category under the same name — the visitor gets a toggle and a real choice, and
     * the site's default is "ask" rather than "take the plugin's word for it". Nothing is hidden either way:
     * the admin audit lists the claim, approved or not.
     *
     * @return the effective category, or {@code null} for a service whose declaration is unusable
     */
    private String effectiveCategory(String pluginId, PluginManifest.Service service) {
        String category = normalize(service.category());
        if (category == null || !CATEGORY_NECESSARY.equals(category)) {
            return category;
        }
        return approvals.isApproved(pluginId, service) ? CATEGORY_NECESSARY : CATEGORY_NECESSARY_UNAPPROVED;
    }

    /**
     * Whether a service's origins belong in every visitor's policy regardless of what they chose.
     *
     * <p>True only for an <em>approved</em> necessary claim. An unapproved one is prompted, so it is subject
     * to {@code grantedCategories} like anything else.
     */
    private boolean isUnconditional(String pluginId, PluginManifest.Service service) {
        return CATEGORY_NECESSARY.equals(effectiveCategory(pluginId, service));
    }

    private static List<PluginManifest.Service> declaredServices(PluginRegistration registration) {
        PluginManifest manifest = registration.manifest();
        if (manifest == null || manifest.consent() == null) {
            return List.of();
        }
        return manifest.consent().servicesOrEmpty();
    }

    private static ServiceView view(PluginManifest.Service service) {
        return new ServiceView(service.name(), service.provider(), service.privacyUrl(),
                service.transfersToThirdCountry(), storageViews(service));
    }

    private static List<StorageView> storageViews(PluginManifest.Service service) {
        return service.storageOrEmpty().stream()
                .map(item -> new StorageView(item.name(), item.type(), item.purpose(), item.duration()))
                .toList();
    }

    private String privacySlug() {
        return legal.footer(siteConfig.get().getDefaultLocale()).stream()
                .filter(entry -> "privacy".equalsIgnoreCase(entry.role()))
                .map(FooterEntry::slug)
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String category) {
        return category == null || category.isBlank() ? null : category.trim().toLowerCase();
    }

    /**
     * SHA-256, truncated to 16 hex characters. This guards against a stale answer, not against an attacker —
     * a visitor who edits their own {@code localStorage} is only fooling themselves.
     */
    private static String digest(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
