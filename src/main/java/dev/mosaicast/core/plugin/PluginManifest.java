// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.PlatformApi;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed {@code plugin.json} manifest of a plugin (ARCHITECTURE §7.2). Deserialized from the plugin
 * folder at load time; unknown fields are tolerated for forward compatibility.
 *
 * <p>Validation ({@link #validate()}) is the stability anchor of the plugin system: an incompatible
 * {@code platformApi}, a declared relational {@code schema} (deferred to a later milestone, §7.6), or a
 * slot targeting an unknown region is rejected at load — the plugin is disabled, not fatal (§7.8).
 *
 * @param id          the plugin id (its folder name and HTTP namespace); never blank
 * @param version     the plugin's own version
 * @param platformApi the plugin-contract version the plugin was built against (must be compatible with
 *                    {@link PlatformApi#VERSION})
 * @param name        the human-readable plugin name
 * @param backend     backend entry points (PF4J extension classes)
 * @param frontend    frontend bundle entry + custom-element tags
 * @param slots       where the plugin mounts its elements in the shell
 * @param storage     {@code "doc"} (generic doc store, the v1 default) or {@code "schema"} (deferred)
 * @param config      declared config fields, keyed by field name
 * @param data        the doc-store access floors and backend-owned keys (§7.2); absent means the closed
 *                    default and nothing reserved
 * @param consent     declared consent categories / external sources
 * @param nav         ways into a {@code page} plugin, offered to the shell's navigation menu (§7.3). Absent
 *                    on a page plugin means one default entry at its root; absent on any other plugin means
 *                    nothing, and declaring entries without a {@code page} slot is rejected
 * @param license     SPDX identifier of the plugin's own licence (e.g. {@code AGPL-3.0-or-later}); shown on
 *                    the public About page. Free-form and never validated — an unrecognised or absent value
 *                    must not stop a plugin loading, because credit is not a correctness concern
 * @param author      who wrote the plugin, as they wish to be credited
 * @param homepage    the plugin's own page — its repository, docs or project site
 * @param attribution a URL for whoever the plugin wants to credit beyond its author: the source of its data,
 *                    an upstream library, an artist. Separate from {@code homepage} because "where this
 *                    lives" and "who deserves credit for it" are not the same link, and a plugin that
 *                    borrows should be able to say so without giving up its own
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
        String id,
        String version,
        String platformApi,
        String name,
        Backend backend,
        Frontend frontend,
        List<Slot> slots,
        PluginStorage storage,
        Map<String, ConfigField> config,
        DataAccess data,
        Blobs blobs,
        Consent consent,
        List<NavEntry> nav,
        // Credit, not contract. Boxed and unvalidated on purpose: Jackson 3 refuses to map a missing value
        // onto a primitive, and `validate()` deliberately says nothing about these — a plugin written
        // before they existed must keep loading, and one that spells its licence oddly is still a working
        // plugin. Additive in both directions (unknown fields are ignored), so no `platformApi` bump.
        String license,
        String author,
        String homepage,
        String attribution) {

    /**
     * The manifest without its navigation or credit fields — everything the host needs to actually run a
     * plugin.
     *
     * Exists so that adding another purely descriptive field does not ripple through every construction
     * site that never cared about them. Jackson always uses the canonical constructor; this is for callers
     * assembling a manifest by hand.
     */
    public PluginManifest(String id, String version, String platformApi, String name, Backend backend,
                          Frontend frontend, List<Slot> slots, PluginStorage storage,
                          Map<String, ConfigField> config, DataAccess data, Blobs blobs, Consent consent) {
        this(id, version, platformApi, name, backend, frontend, slots, storage, config, data, blobs, consent,
                null, null, null, null, null);
    }

    /** The declared nav entries, or an empty list — callers never have to null-check. */
    public List<NavEntry> navOrEmpty() {
        return nav == null ? List.of() : nav;
    }

    /** Whether the plugin declares a {@code page} slot, i.e. whether {@code /p/{id}} renders anything. */
    public boolean declaresPage() {
        return slots != null && slots.stream().anyMatch(s -> PLACEMENT_PAGE.equals(s.placement()));
    }

    /**
     * Strips a leading slash and any {@code .}/{@code ..} segments from a nav path.
     *
     * <p>The same normalisation the shell applies to {@code ctx.route.navigate}, for the same reason: an
     * entry addresses a subpath <em>inside</em> its own plugin, and neither a core route nor another
     * plugin's page is nameable from here. Unlike the runtime version this one only cleans — a path that
     * needed cleaning is rejected at load, because silently rewriting an author's declaration into a
     * different URL is worse than telling them it was wrong.
     */
    static String normalizeNavPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] segments = path.replaceAll("^/+", "").split("/");
        StringBuilder cleaned = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            if (!cleaned.isEmpty()) {
                cleaned.append('/');
            }
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    /** Storage kinds a manifest may declare; see {@link PluginStorage} for the two shapes it takes. */
    public static final String STORAGE_DOC = PluginStorage.DOC;
    public static final String STORAGE_SCHEMA = PluginStorage.SCHEMA;

    /**
     * The full-page region behind a plugin deep link {@code /p/{pluginId}/…} (§6.4). A plugin opts in by
     * declaring a slot here; the host reserves the route either way and hands the subpath to the element as
     * {@code ctx.route}.
     */
    public static final String PLACEMENT_PAGE = "page";

    /** The slot placements the shell defines (ARCHITECTURE §7.3); a slot targeting anything else is rejected. */
    public static final Set<String> KNOWN_PLACEMENTS =
            Set.of("top", "card", "main", "sidebar", "player", "feed", "site", "admin", PLACEMENT_PAGE);

    /** Config field types the generated admin form can render and type-check (§7.2). */
    public static final String CONFIG_TYPE_STRING = "string";
    public static final String CONFIG_TYPE_NUMBER = "number";
    public static final String CONFIG_TYPE_BOOLEAN = "boolean";
    public static final Set<String> KNOWN_CONFIG_TYPES =
            Set.of(CONFIG_TYPE_STRING, CONFIG_TYPE_NUMBER, CONFIG_TYPE_BOOLEAN);

    /** Roles a config field may be delegated to; anything else (including a fan) is rejected (§8.5). */
    public static final String EDITABLE_BY_ADMIN = "admin";
    public static final String EDITABLE_BY_PODCASTER = "podcaster";
    public static final Set<String> KNOWN_EDITABLE_BY = Set.of(EDITABLE_BY_ADMIN, EDITABLE_BY_PODCASTER);

    /**
     * The access floors of the plugin's generic data surface (ARCHITECTURE §7.2/§7.6).
     *
     * <p><strong>Declared, never derived.</strong> The host used to infer these from slot {@code visibleTo},
     * taking the <em>minimum</em> across all slots as the read floor — so a plugin with one anonymous display
     * slot served its entire doc store anonymously, including whatever an admin-only slot had written. Access
     * to a data surface has nothing to do with which UI regions a plugin happens to mount into, and inferring
     * one from the other coupled two unrelated decisions.
     *
     * <p><strong>A floor says who, not which key.</strong> Authorization on the data surface is per plugin,
     * not per document, so every caller above {@code writableBy} may overwrite or delete <em>any</em>
     * shared-scope key — including one the plugin's own backend computed, because the host cannot tell a
     * scheduled write from a {@code curl}. {@code backendOwned} is the exception a plugin can declare, and
     * the only per-key rule on this surface.
     *
     * @param readableBy   who may read; absent means the write floor, not anonymous — a plugin that says
     *                     nothing gets the closed answer rather than the open one
     * @param writableBy   who may write; absent means {@code podcaster}, and {@code anonymous} is not allowed
     * @param backendOwned keys only the plugin's backend may write: an exact key, a {@code *}-terminated
     *                     prefix, or the bare {@code *} ({@link DocStore#BACKEND_OWNED_PATTERN}). Clients may
     *                     still read them; a client {@code PUT}/{@code DELETE} is a 403. Absent means nothing
     *                     is reserved.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataAccess(String readableBy, String writableBy, List<String> backendOwned) {

        /** The declared write floor, or the conservative default. */
        public String writableByOrDefault() {
            return writableBy == null || writableBy.isBlank()
                    ? EDITABLE_BY_PODCASTER : writableBy.trim().toLowerCase();
        }

        /** The declared read floor, or — deliberately — the write floor. */
        public String readableByOrDefault() {
            return readableBy == null || readableBy.isBlank()
                    ? writableByOrDefault() : readableBy.trim().toLowerCase();
        }

        /** The declared backend-owned key patterns; empty when the manifest reserves nothing. */
        public List<String> backendOwnedOrEmpty() {
            return backendOwned == null ? List.of() : backendOwned;
        }
    }

    /**
     * The storage declaration, defaulting to the doc store.
     *
     * <p>Absent means {@code doc}: the generic store is what a plugin gets by saying nothing, and §7.6 makes
     * it the default rather than something to opt into.
     */
    public PluginStorage storageOrDefault() {
        return storage == null ? PluginStorage.doc() : storage;
    }

    /** The resolved schema entities, or empty for a doc-only plugin. Validated at load. */
    public Map<String, PluginSchemaValidator.Entity> schemaEntities() {
        return PluginSchemaValidator.resolve(id, storageOrDefault());
    }

    /** Roles the data floors accept. Unlike {@code editableBy}, a read floor may be anonymous. */
    public static final String ACCESS_ANONYMOUS = "anonymous";
    public static final String ACCESS_FAN = "fan";
    public static final Set<String> KNOWN_DATA_ACCESS =
            Set.of(ACCESS_ANONYMOUS, ACCESS_FAN, EDITABLE_BY_PODCASTER, EDITABLE_BY_ADMIN);

    /** The floors applied when a manifest declares no {@code data} block: closed, and never anonymous. */
    public static final DataAccess DEFAULT_DATA_ACCESS = new DataAccess(null, null, null);

    /** The effective floors, whether or not the manifest declared them. */
    public DataAccess dataOrDefault() {
        return data == null ? DEFAULT_DATA_ACCESS : data;
    }

    /**
     * What a plugin asks to be allowed to store as files (ARCHITECTURE §11, §7.2).
     *
     * <p>Declared, never derived — the same rule as the data floors. A plugin's appetite for disk is
     * something whoever installs it should be able to read off the manifest, and an operator caps every
     * number here from their own configuration, so what is granted may be less than what is asked.
     *
     * <p>An absent block means <strong>no file storage at all</strong>: {@code ctx.blobs} is null and the
     * HTTP surface answers 404, indistinguishably from an unknown plugin. Storage is opt-in because the
     * alternative — every plugin able to write bytes by default — is the first unbounded write a plugin
     * could make.
     *
     * @param maxFileBytes the largest single file, or null to take the operator's ceiling
     * @param quotaBytes   the total this plugin may occupy, or null to take the operator's ceiling
     * @param mimeTypes    the content types it wants to store; intersected with the operator's allow-list
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Blobs(Long maxFileBytes, Long quotaBytes, List<String> mimeTypes) {

        /** The declared types, lower-cased and never null. */
        public List<String> mimeTypesOrEmpty() {
            return mimeTypes == null ? List.of()
                    : mimeTypes.stream().filter(java.util.Objects::nonNull)
                            .map(type -> type.trim().toLowerCase(java.util.Locale.ROOT)).toList();
        }
    }

    /** Whether this plugin declared any file storage at all. */
    public boolean declaresBlobs() {
        return blobs != null;
    }

    /** Backend entry points. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Backend(String basePath, List<String> extensions) {
    }

    /** Frontend bundle: the ES entry file (served from the plugin's {@code assets/}) and its element tags. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Frontend(String entry, List<String> elements) {
    }

    /**
     * One mount point: {@code element} rendered at {@code placement} for the given {@code scope}, visible to
     * users at or above {@code visibleTo}, ordered by {@code order} within the region (ties broken by id).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slot(String scope, String element, String placement, String visibleTo, Integer order) {
    }

    /**
     * One way into a {@code page} plugin, offered to the shell's navigation menu.
     *
     * <p>A page plugin owns {@code /p/{id}/*} but nothing links to it, so without this a visitor has to
     * know the URL. A plugin declares <em>what</em> its entrances are; the host decides where and how they
     * render — navigation is host chrome, and a plugin drawing its own header link would style it its own
     * way (the {@code top} region already allows that, and no plugin uses it).
     *
     * <p>Declaring none is normal: a page plugin with no {@code nav} gets one default entry at its root,
     * labelled with its manifest name. Several are for plugins with genuinely distinct entrances — a wiki's
     * front page, a random article, a "new page" form only a podcaster should see.
     *
     * @param path      the subpath below {@code /p/{id}/}; empty or {@code null} means the plugin's root.
     *                  Normalised and range-checked at load, so an entry can never point outside its own
     *                  plugin
     * @param label     what the menu shows. Required — an entry nobody can read is not an entry. Taken from
     *                  the manifest verbatim and <strong>not translated</strong>, matching the consent
     *                  declarations; per-locale labels would be additive later
     * @param icon      a published {@code --mc-icon-*} name (§12.3), without the prefix. Optional, and
     *                  <strong>never validated</strong>: an unknown name falls back at render. Rejecting a
     *                  whole plugin over a mistyped decoration would be disproportionate, and the host holds
     *                  no icon list — the palette lives in generated CSS, and a copy here would be a second
     *                  source of truth that could disagree with it
     * @param visibleTo the minimum role, as everywhere else; absent means anonymous
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NavEntry(String path, String label, String icon, String visibleTo) {

        /** The path with a leading slash and any {@code .}/{@code ..} segments removed. */
        public String normalizedPath() {
            return normalizeNavPath(path);
        }
    }

    /**
     * A declared config field: its type, default value (raw JSON) and who may edit it (ARCHITECTURE §7.2).
     * The host renders these as a generic admin form — plugins never build their own config UI — so the
     * declaration has to carry enough to render and validate an input.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigField(String type, @JsonProperty("default") JsonNode defaultValue, String editableBy) {

        /** The role a field defaults to when the manifest names none: the most restrictive one. */
        public String editableByOrDefault() {
            return editableBy == null || editableBy.isBlank() ? EDITABLE_BY_ADMIN : editableBy.toLowerCase();
        }

        /**
         * Whether {@code value} is a legal setting for this field. JSON null always passes: it is how an
         * admin clears an override and falls back to the manifest default.
         */
        public boolean accepts(JsonNode value) {
            if (value == null || value.isNull()) {
                return true;
            }
            return switch (type == null ? "" : type.toLowerCase()) {
                case CONFIG_TYPE_STRING -> value.isString();
                case CONFIG_TYPE_NUMBER -> value.isNumber();
                case CONFIG_TYPE_BOOLEAN -> value.isBoolean();
                default -> false;
            };
        }
    }

    /**
     * Declared consent surface (ARCHITECTURE §12.5). A plugin that touches no third party omits {@code
     * consent} entirely and the site stays banner-free.
     *
     * <p>The legacy {@code {categories, externalSources}} form is gone as of {@code platformApi 0.4.0}:
     * category slugs and bare hostnames cannot produce a notice that satisfies §25 TDDDG / Art. 5(3) ePD,
     * which needs each stored item named with its purpose, lifetime, provider and third-country status — and
     * they force the notice to talk about "plugins" to visitors who care about cookies and companies.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consent(List<Service> services) {

        /** The services this plugin declares, never null. */
        public List<Service> servicesOrEmpty() {
            return services == null ? List.of() : services;
        }
    }

    /**
     * One third-party service a plugin contacts.
     *
     * @param id                   stable identifier within the plugin
     * @param name                 what a visitor would recognise, e.g. "Plausible Analytics"
     * @param provider             the legal entity operating it — the company, not the plugin
     * @param category             the consent category gating it; {@code necessary} is never prompted for
     * @param privacyUrl           the provider's own privacy policy
     * @param hosts                every origin it is contacted on; <strong>also the CSP allow-list</strong>
     * @param thirdCountryTransfer whether personal data leaves the EU/EEA
     * @param storage              what it stores on the device
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Service(String id, String name, String provider, String category, String privacyUrl,
                          List<String> hosts, Boolean thirdCountryTransfer, List<StorageItem> storage) {

        /**
         * Whether the service transfers data outside the EU/EEA; absent means no.
         *
         * <p>Boxed on purpose: Jackson 3 refuses to map a missing or null value onto a primitive, so a
         * {@code boolean} here would make a manifest that simply omits the flag fail to parse — with a raw
         * deserialization error instead of this class's own validation message. Optional fields have to
         * behave as optional.
         */
        public boolean transfersToThirdCountry() {
            return Boolean.TRUE.equals(thirdCountryTransfer);
        }

        public List<String> hostsOrEmpty() {
            return hosts == null ? List.of() : hosts;
        }

        public List<StorageItem> storageOrEmpty() {
            return storage == null ? List.of() : storage;
        }
    }

    /**
     * One item a service stores on the visitor's device.
     *
     * @param name     the cookie / key name as it appears in the browser
     * @param type     {@code cookie}, {@code localStorage} or {@code sessionStorage}
     * @param purpose  plain-language reason, shown to visitors
     * @param duration how long it lasts, e.g. {@code session}, {@code 12 months}, {@code persistent}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StorageItem(String name, String type, String purpose, String duration) {
    }

    /**
     * Validates the manifest against the host contract, throwing {@link PluginValidationException} on the
     * first problem. Called at load time; a throw records the plugin as rejected with the message as reason.
     *
     * @throws PluginValidationException if the manifest is unusable or incompatible
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new PluginValidationException("manifest has no id");
        }
        if (platformApi == null || !isPlatformApiCompatible(platformApi)) {
            throw new PluginValidationException(
                    "platformApi %s is incompatible with host %s".formatted(platformApi, PlatformApi.VERSION));
        }
        // Resolving the schema is the validation: it refuses any entity, field name or type spec that
        // could not become safe DDL, and the result is what the migration runner provisions from.
        PluginSchemaValidator.resolve(id, storageOrDefault());
        if (slots != null) {
            for (Slot slot : slots) {
                if (slot.placement() == null || !KNOWN_PLACEMENTS.contains(slot.placement())) {
                    throw new PluginValidationException("unknown slot placement: " + slot.placement());
                }
            }
        }
        validateConfig();
        validateData();
        validateBlobs();
        validateConsent();
        validateNav();
    }

    /**
     * Validates declared navigation entries (§7.3).
     *
     * <p>What is rejected is what would produce a link that does not work, or one nobody can read:
     *
     * <ul>
     *   <li><strong>Entries without a {@code page} slot.</strong> {@code /p/{id}} renders nothing for such a
     *       plugin, so every entry would be a link into a 404. Failing at load names the contradiction; the
     *       alternative is a menu item that is broken for as long as nobody clicks it.</li>
     *   <li><strong>A blank label.</strong> The menu has nothing to draw.</li>
     *   <li><strong>A path that needed normalising.</strong> A leading slash or a {@code ..} segment is
     *       refused rather than quietly cleaned: rewriting an author's declaration into a <em>different</em>
     *       URL and then linking to it is a worse outcome than saying it was wrong.</li>
     *   <li><strong>Two entries on the same path.</strong> They are the same destination, and the admin
     *       overrides that come later are keyed by it — duplicates would make an ordering ambiguous.</li>
     * </ul>
     *
     * <p>{@code icon} is deliberately absent from this list. It is decoration, and refusing to load a whole
     * plugin over a mistyped one would be disproportionate; an unknown name falls back when rendered. The
     * host also has no list to check against — the icon palette lives in generated CSS, and a copy here
     * would be a second source of truth free to disagree with it.
     */
    private void validateNav() {
        List<NavEntry> entries = navOrEmpty();
        if (entries.isEmpty()) {
            return;
        }
        if (!declaresPage()) {
            throw new PluginValidationException(
                    "nav entries declared without a `page` slot — /p/" + id + " would render nothing");
        }
        Set<String> seen = new HashSet<>();
        for (NavEntry entry : entries) {
            if (entry.label() == null || entry.label().isBlank()) {
                throw new PluginValidationException("nav entry has no label");
            }
            String declared = entry.path() == null ? "" : entry.path();
            String normalized = normalizeNavPath(declared);
            if (!declared.equals(normalized)) {
                throw new PluginValidationException(
                        "nav path must be a plain subpath of the plugin, without a leading `/` or `..`: "
                                + declared);
            }
            if (!seen.add(normalized)) {
                throw new PluginValidationException("duplicate nav path: " + describePath(normalized));
            }
        }
    }

    /** Names the plugin root readably — {@code ""} in an error message reads as a missing value. */
    private static String describePath(String path) {
        return path.isEmpty() ? "(the plugin root)" : path;
    }

    /**
     * Validates the {@code blobs} block's own shape — the parts that are wrong no matter how the install is
     * configured.
     *
     * <p>The <em>ceilings</em> are deliberately not checked against the operator's here: a plugin asking for
     * more than an install allows is not malformed, it is simply granted less, and refusing to load it would
     * make a plugin's portability depend on the most restrictive install it might ever meet. What is refused
     * is a declaration that could never work anywhere: a non-positive limit, or a type list that is present
     * but names nothing usable. Both are silent failures otherwise — a plugin that loads, declares file
     * storage, and rejects every upload.
     *
     * <p>{@code image/svg+xml} is refused by name. §12.2 keeps SVG out of uploads because it is a script
     * container wearing an image's extension; a manifest asking for it is asking for the one thing the
     * platform does not do, and saying so at load beats a refusal per upload with no explanation.
     */
    private void validateBlobs() {
        if (blobs == null) {
            return;
        }
        for (Long limit : new Long[] {blobs.maxFileBytes(), blobs.quotaBytes()}) {
            if (limit != null && limit <= 0) {
                throw new PluginValidationException(
                        "blobs limits must be positive; got " + limit);
            }
        }
        if (blobs.mimeTypes() != null && blobs.mimeTypesOrEmpty().isEmpty()) {
            throw new PluginValidationException(
                    "blobs.mimeTypes is present but names no type — omit it to take the operator's list");
        }
        if (blobs.mimeTypesOrEmpty().contains("image/svg+xml")) {
            throw new PluginValidationException(
                    "blobs.mimeTypes may not include image/svg+xml — SVG uploads are never accepted (§12.2)");
        }
    }

    /**
     * The consent declaration is what the visitor-facing notice is generated from <em>and</em> what the CSP
     * allows, so a malformed one is rejected at load rather than producing a notice that is quietly wrong.
     *
     * <p>A plugin that declares nothing passes: no consent block means no third parties, which is the
     * banner-free default.
     */
    /**
     * The grammar a consent category must fit, which is the consent cookie's grammar
     * ({@code ConsentCookie}, {@code writeConsentCookie}). Kept here so a manifest that could never be
     * granted is refused at load rather than at the network layer, silently, on a visitor's machine.
     */
    private static final java.util.regex.Pattern CATEGORY_TOKEN =
            java.util.regex.Pattern.compile("[a-z0-9_-]{1,40}", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The grammar of one {@code data.backendOwned} entry, taken from the SDK so the two cannot drift: an
     * exact key, a key-legal prefix with a single trailing {@code *}, or the bare {@code *}.
     */
    private static final java.util.regex.Pattern BACKEND_OWNED =
            java.util.regex.Pattern.compile(DocStore.BACKEND_OWNED_PATTERN);

    /**
     * Validates the declared data floors and the backend-owned key patterns.
     *
     * <p>{@code writableBy: "anonymous"} is refused outright: an unauthenticated write has no owner, nothing
     * to rate-limit against and nobody to hold responsible, and a plugin that wants public participation
     * wants {@code fan} plus a login.
     *
     * <p>A malformed {@code backendOwned} entry is refused rather than dropped, because dropping it is the
     * worst outcome available: the plugin would load, the manifest would claim a key is the backend's, and
     * the host would enforce nothing. A security declaration that fails has to fail loudly.
     */
    private void validateData() {
        if (data == null) {
            return;
        }
        for (String floor : new String[] {data.readableBy(), data.writableBy()}) {
            if (floor != null && !floor.isBlank()
                    && !KNOWN_DATA_ACCESS.contains(floor.trim().toLowerCase())) {
                throw new PluginValidationException(
                        "data floor '%s' is not one of %s".formatted(floor, KNOWN_DATA_ACCESS));
            }
        }
        if (ACCESS_ANONYMOUS.equals(data.writableByOrDefault())) {
            throw new PluginValidationException(
                    "data.writableBy may not be 'anonymous' — a write needs a signed-in user to belong to");
        }
        for (String pattern : data.backendOwnedOrEmpty()) {
            // Not trimmed before matching, deliberately: the floors are a closed vocabulary of role names,
            // but this is a key pattern, and a key may not contain whitespace either. Accepting " stats"
            // would accept a manifest that does not say what it means.
            if (pattern == null || !BACKEND_OWNED.matcher(pattern).matches()) {
                throw new PluginValidationException(
                        ("data.backendOwned entry '%s' is not usable: it must be an exact key, a prefix "
                                + "ending in a single '*', or the bare '*'").formatted(pattern));
            }
        }
    }

    private void validateConsent() {
        if (consent == null) {
            return;
        }
        if (consent.services() == null) {
            throw new PluginValidationException(
                    "consent must declare services[]; the categories/externalSources form was removed in "
                            + "platformApi 0.4 — see the SDK README for the shape");
        }
        for (Service service : consent.servicesOrEmpty()) {
            if (service.name() == null || service.name().isBlank()) {
                throw new PluginValidationException("consent service '%s' has no name — visitors are shown "
                        + "this, so it cannot be blank".formatted(service.id()));
            }
            if (service.category() == null || service.category().isBlank()) {
                throw new PluginValidationException(
                        "consent service '%s' declares no category".formatted(service.name()));
            }
            requireUsableCategory(service);
            for (String host : service.hostsOrEmpty()) {
                requireAbsoluteOrigin(service, host);
            }
            for (StorageItem item : service.storageOrEmpty()) {
                requireUsableStorageItem(service, item);
            }
        }
    }

    /**
     * A category has to survive the round trip through the visitor's consent cookie, which is what carries the
     * decision to the server and therefore into the CSP.
     *
     * <p>That cookie is a dot-separated list of tokens matching {@code [a-z0-9_-]{1,40}}, and nothing checked
     * a declared category against it. So {@code analytics.plausible} — or anything over 40 characters, or with
     * an accent in it — produced a normal toggle, was granted by the visitor, made {@code has()} return true
     * so the plugin proceeded, and then never reached the cookie: the server saw no grant, the origins stayed
     * out of the policy, and every request failed at the network layer while the UI insisted consent had been
     * given. Failing closed is right; failing closed <em>silently</em>, on a valid-looking manifest, is not.
     * Rejecting at load turns an invisible runtime contradiction into a message the author can act on.
     */
    private static void requireUsableCategory(Service service) {
        String category = service.category().trim();
        if (!CATEGORY_TOKEN.matcher(category).matches()) {
            throw new PluginValidationException(
                    ("consent service '%s' category '%s' is not usable: it must be 1-40 characters of "
                            + "a-z, 0-9, '_' or '-' (no dots — the consent cookie separates categories with "
                            + "them, so a dotted id could never be granted)")
                            .formatted(service.name(), category));
        }
    }

    /**
     * A storage item's {@code name} is what the shell's purge sweep matches keys against, so an unusable one
     * disables enforcement rather than merely looking untidy.
     *
     * <p>{@link StorageItem} is an all-optional record under {@code @JsonIgnoreProperties}, so an author who
     * wrote {@code "key"} where the schema says {@code "name"} yields a null that reached the browser and threw
     * inside the sweep. And a bare {@code "*"} matched every key on the origin, switching the sweep and the
     * storage audit off for core's keys as much as the plugin's own. Both are caught here now, so the client
     * never sees them.
     */
    private static void requireUsableStorageItem(Service service, StorageItem item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            throw new PluginValidationException(
                    ("consent service '%s' declares a storage item with no name — the shell matches device "
                            + "keys against it, so it cannot be blank (did you write \"key\" instead of "
                            + "\"name\"?)").formatted(service.name()));
        }
        String name = item.name().trim();
        if (name.equals("*")) {
            throw new PluginValidationException(
                    ("consent service '%s' declares the storage name '*', which matches every key on the "
                            + "site — including core's. A wildcard needs a prefix, e.g. 'myplugin.*'")
                            .formatted(service.name()));
        }
        if (name.indexOf('*') >= 0 && !name.endsWith("*")) {
            throw new PluginValidationException(
                    ("consent service '%s' storage name '%s' uses '*' in the middle; the wildcard is a "
                            + "trailing prefix match, not a glob").formatted(service.name(), name));
        }
    }

    /**
     * A host doubles as a CSP origin, which needs a scheme to mean anything — {@code plausible.example} is
     * not an origin and would silently never match. Rejecting it here beats a plugin whose embeds fail to
     * load with consent granted and no explanation.
     *
     * <p>A leading {@code *.} subdomain wildcard is legal in a CSP {@code host-source} and is the normal way
     * to front Plausible, Matomo or a CDN. {@code URI.getHost()} returns null for it — {@code *} is not legal
     * in a hostname, so the authority parses as registry-based — which meant validation rejected a valid
     * policy source, and, because this runs before the backend starts, rejected the <em>entire plugin</em>
     * while telling the author their correct origin was invalid. The wildcard label is stripped before the
     * check and the host is validated as the rest of the name.
     */
    private static void requireAbsoluteOrigin(Service service, String host) {
        if (host == null || host.isBlank()) {
            throw new PluginValidationException(
                    "consent service '%s' declares a blank host".formatted(service.name()));
        }
        if (!host.startsWith("https://") && !host.startsWith("http://")) {
            throw new PluginValidationException(
                    "consent service '%s' host '%s' needs a scheme (https://%s)"
                            .formatted(service.name(), host, host));
        }
        // Only as the leading label, and only followed by something: `*` alone would widen the policy to every
        // origin, which is not a declaration of anything.
        String candidate = host.replaceFirst("^(https?://)\\*\\.", "$1");
        if (candidate.equals(host) && host.indexOf('*') >= 0) {
            throw new PluginValidationException(
                    ("consent service '%s' host '%s': a CSP wildcard is only valid as a leading subdomain "
                            + "label, e.g. https://*.plausible.io").formatted(service.name(), host));
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null) {
                throw new PluginValidationException(
                        "consent service '%s' host '%s' is not a valid origin".formatted(service.name(), host));
            }
        } catch (IllegalArgumentException e) {
            throw new PluginValidationException(
                    "consent service '%s' host '%s' is not a valid origin".formatted(service.name(), host));
        }
    }

    /**
     * The host renders declared config fields as a form and type-checks admin input against them, so a field
     * it cannot render or check is rejected at load rather than surfacing as a broken admin page.
     */
    private void validateConfig() {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, ConfigField> entry : config.entrySet()) {
            ConfigField field = entry.getValue();
            String type = field.type() == null ? null : field.type().toLowerCase();
            if (type == null || !KNOWN_CONFIG_TYPES.contains(type)) {
                throw new PluginValidationException(
                        "config field '%s' has unknown type: %s".formatted(entry.getKey(), field.type()));
            }
            if (!KNOWN_EDITABLE_BY.contains(field.editableByOrDefault())) {
                throw new PluginValidationException(
                        "config field '%s' has unknown editableBy: %s".formatted(entry.getKey(), field.editableBy()));
            }
            if (!field.accepts(field.defaultValue())) {
                throw new PluginValidationException(
                        "config field '%s' default does not match declared type %s".formatted(entry.getKey(), type));
            }
        }
    }

    /**
     * A plugin is compatible when it declares the same major and minor as the host contract. Pre-1.0 the
     * minor carries breaking changes, so an exact {@code major.minor} match is required (patch is free).
     */
    private static boolean isPlatformApiCompatible(String declared) {
        int[] want = majorMinor(PlatformApi.VERSION);
        int[] got = majorMinor(declared);
        return got != null && want != null && got[0] == want[0] && got[1] == want[1];
    }

    private static int[] majorMinor(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
