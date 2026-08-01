<!--
SPDX-License-Identifier: AGPL-3.0-or-later
SPDX-FileCopyrightText: 2026 The Mosaicast Authors
-->

# Changelog

All notable changes to **mosaicast-core** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The minor version tracks the build milestone
(M1 = `0.1.x`, M2 = `0.2.x`, …) and is independent of the plugin-contract (SDK) version.

## [Unreleased]

### Changed

- **Consent is now written for visitors (`0.5.11`, ARCHITECTURE §12.5)** — the declaration half shipped in
  `0.5.9`; this is the half people actually read. The old banner said *"An installed plugin wants to load
  content from third parties"* and listed `requested by: sample`, which is the site's architecture, not a
  question anyone can answer. It also rendered raw category slugs, and its settings link existed **only**
  when a plugin declared something — so a core-only install had no withdrawal surface at all, while still
  writing `mc.locale`, `mc.site`, `mc.progress.*` and `mc.consent` to the device.
  - **Visitors are told about services and companies.** Layer one names the providers involved and links the
    privacy page; the settings name each service, who operates it, whether data leaves the EU/EEA, and every
    item it stores with purpose and lifetime — from the manifest, so the notice cannot drift from what loads.
    The words *plugin*, *slot* and *manifest* do not appear in visitor-facing text, and the endpoint that
    feeds it has nowhere to put a plugin id.
  - **Allow and refuse are the same button in the same row.** A quieter reject is a dark pattern with a case
    history (DSK, Nov 2024; OLG Köln 2025), so both are the accent style and a test asserts the wording.
  - **One settings component in three places** — `/cookies`, appended below the legal page marked `privacy`,
    and inside the banner — because withdrawal has to be as easy as granting, and three near-identical
    surfaces are how that stops being true. The footer link is now **unconditional**.
  - **A stored answer can now stop counting.** `decided` used to be a single boolean, so a newly installed
    plugin silently inherited a decision taken before it existed. The record now carries `decidedAt` and the
    server's **declaration fingerprint**: change a provider, a host or even a cookie's lifetime and the
    fingerprint moves, so the question is asked again. Answers also expire after twelve months.
  - **Global Privacy Control is honoured** as a refusal — and, since it is an answer, without a banner. An
    explicit choice in the settings still overrides it.
  - **Playback position gets an off switch, not a consent gate.** It is first-party, local, never profiled
    and written only after a deliberate press of play, so gating it would trade a real feature for a fake
    choice. Switching it off also deletes the positions already stored.
  - **The receipt stays on the visitor's device** (`mc.consent`, readable and exportable from the settings).
    No server-side consent table: that would be a new store of personal data, with its own legal basis and
    retention, created to prove something about visitors who are anonymous here. The operator's half is
    **Admin → Consent** (`GET /api/admin/consent`) — every declared service attributed to its plugin, the
    resulting CSP allow-list, and the fingerprint.
  - **The core's own storage is generated, not written down.** `CoreStorageInventory` produces the "always
    active" list from constants, with purposes and durations travelling as i18n keys so the disclosure reads
    correctly in both locales. Flyway `V16` replaces the hand-written list in the seeded privacy page — which
    had already drifted, having never mentioned `mc.consent` — with a pointer to it, by targeted replacement
    so an operator's own edits to that template survive.

- **The shell has a design-token layer, and the stylesheet is split (`0.5.10`, ARCHITECTURE §12.3)** — the
  `--mc-*` custom properties are a contract with plugins, and until now that contract was eight colours.
  Everything else a plugin might want to match — spacing, radii, elevation, type scale, motion — did not
  exist, so a plugin author had two options: hard-code values that break in dark mode, or guess. The shell
  had the same problem from the inside: 1482 lines with two `transition`s, two `box-shadow`s and no focus
  ring worth the name.
  - **New tokens** in `frontend/src/styles/tokens.css`: surfaces and state layers (`--mc-surface-2`,
    `--mc-hover`, `--mc-active`, `--mc-accent-soft`, `--mc-border-strong`, `--mc-overlay`, `--mc-focus`),
    feedback colours, a space scale, radii, three elevations, a type scale, motion, and layout constants
    (`--mc-container`, `--mc-player-height`). The eight original colour names are untouched.
  - **Derived, not duplicated.** The seed generator only writes the eight colours at runtime, so every other
    colour token is a `color-mix()` over them. An admin dragging the accent moves the hover layer and the
    strong border with it, instead of leaving them at their light-theme defaults. Only the feedback colours
    and the shadow alphas — which cannot be derived and stay readable — are written per theme.
  - **Plugins get this for free**, because custom properties inherit across a shadow boundary. Only the eight
    colours are also delivered as JS (`ctx.theme`); everything else is CSS-only and needs no SDK bump. The
    token table is in the README under *Theming a plugin*.
  - **`styles.css` is now an `@import` manifest** over `styles/{tokens,base,chrome,feed,detail,player,forms,
    admin,consent}.css`. Rules moved verbatim — this is a split, not a restyle.
  - **Accessibility baseline:** one `:focus-visible` ring for every interactive element (declared on the
    elements, not a class, so a new surface inherits it), a 32px minimum target, `color-scheme` per theme,
    and `prefers-reduced-motion` honoured both through the motion tokens and as a catch-all.
- **Manifest `consent.services[]` (`0.5.9`, ARCHITECTURE §12.5):** the plugin consent declaration moves from
  category slugs plus bare hostnames to **named services**, matching what SDK `0.4.0` documents. Each service
  carries `name`, `provider` (the operating company), `category`, `privacyUrl`, `hosts`,
  `thirdCountryTransfer` and a `storage[]` list of what it puts on the device with purpose and lifetime.
  - **Why:** a notice that satisfies §25 TDDDG / Art. 5(3) ePD has to name each stored item, its purpose, its
    lifetime, its provider and whether data leaves the country. Slugs and hostnames cannot produce that, and
    they forced the notice to talk about "plugins" to visitors who care about cookies and companies.
  - **The legacy form is rejected at load**, with a message naming the replacement — a plugin that kept it
    would otherwise load with its consent declaration silently dropped, meaning no banner and no CSP origins
    for third parties it really does contact.
  - Validation also rejects a service with no name or category, and a **host without a scheme**: hosts double
    as CSP origins, and `plausible.example` is not an origin — it would never match, so the plugin's embeds
    would fail with consent granted and nothing to explain why.
  - `necessary` services are never asked about but still contribute their origins to the CSP and their
    storage to the disclosure — they load without being asked, so they must be allowed and disclosed.
  - Optional fields may be omitted (`thirdCountryTransfer` is boxed, because Jackson 3 refuses to map a
    missing value onto a primitive and would fail the parse before validation could explain anything).

- **Spring Boot 4.1, Jackson 3 and plugin contract `0.4.0` (`0.5.8`)** — one coordinated move, because each
  blocks the others: Boot 3.4 is past OSS support, Boot 4 defaults to Jackson 3, and SDK `0.4.0` types the
  contract on Jackson 3.
  - **Every `0.3.x` plugin is rejected at load until it declares `platformApi: "0.4.0"` and rebuilds.** The
    rejection is visible in **Admin → Logs & health** with its reason, which is why the log viewer shipped
    first. The bundled sample plugin is already migrated (`2.3.0`).
  - **JSONB keeps its exact encoding.** Hibernate detects Jackson by its old `com.fasterxml` package, so from
    Jackson 3 it finds no mapper at all and JSON columns break; core now ships
    `Jackson3JsonFormatMapper`. It deliberately writes temporal values as **numbers**
    (`"duration": 3134.000000000`, `"publishedAt": 1783231200.000000000`) exactly as the old mapper did —
    Jackson 3 would default to ISO-8601 strings and quietly leave the table holding two encodings of the same
    field. Proven against **real rows dumped from a 3.4.1 instance before the upgrade**, kept as fixtures in
    `src/test/resources/premigration/`.
  - **`ctx.logger()`** is implemented: plugins get an SLF4J logger the host names `plugin.<pluginId>`, so a
    plugin's own entries land in the admin viewer attributed to it — including from its own threads and
    scheduled tasks, where a thread-local MDC would arrive empty.
  - **Frontend `ctx`** matches the new contract: `log(level, message)` (routed to
    `POST /api/plugins/{id}/log`), `consent.granted()` / `consent.request(category)` for click-to-load, and
    every `onChange`/`player.on` now returns an **unsubscribe** — a plugin calling one inside a React effect
    uses that as its cleanup, so returning nothing would throw at unmount. `episodeLabels` is native on the
    SDK type now, so the host's intersection type is gone.
  - Build: `-starter-web` → `-starter-webmvc`, Flyway via its starter, Testcontainers 2 artifact names,
    `io.spring.dependency-management` replaced by importing the BOM, `TestRestTemplate` opted into with
    `@AutoConfigureTestRestTemplate`, and JSpecify annotations in place of Spring's removed `lang` ones.
  - **Not in this change:** the `SchemaStore` implementation (`schema()` still returns `null`, `storage:
    "schema"` still rejected) and the manifest `consent.services[]` shape. Until that lands, a plugin
    declaring the new consent form loads but contributes **no** consent categories and no CSP origins.

### Added

- **Admin log & health viewer (`0.5.7`, ARCHITECTURE §13):** everything the host knew about its own failures
  used to go to stdout and die with the container — an operator without a terminal could not find out why a
  plugin had vanished from the site. **Admin → Logs & health** now shows it.
  - **Capture is automatic:** a Logback appender records core's own log statements into a new `app_log` table
    (Flyway `V15`), so the ten existing WARN/ERROR sites — rejected plugin, failed feed poll, the catch-all
    500 — appear without their call sites being touched, and so does every one added later. Throwables are kept as expandable detail; `pluginId`/`feedId` travel via MDC, so entries can be
    filtered by plugin rather than by grepping message text.
  - **Writes never block a request:** entries go through a bounded queue drained by one daemon thread, in
    their own transaction, with drops counted and reported rather than applied as back-pressure. The writer
    cannot feed itself — a failure while storing an entry is reported to stderr, never through SLF4J.
  - **Viewer:** `GET /api/admin/logs` (filter by level, area, plugin, free text, time; paginated — the first
    paginated admin endpoint) plus `/logs/facets` for the dropdowns, an expandable detail row, and an
    off-by-default 10 s auto-refresh that pauses while a row is open.
  - **Health card:** `GET /api/admin/health` answers "is anything broken right now?" — every plugin's state
    *with its rejection reason*, every feed's poll state **including the error text**, error/warning counts for
    the last 24 h, version and uptime.
  - **Plugins can report their own trouble:** `POST /api/plugins/{id}/log`, gated exactly like the doc store
    (active plugin, signed-in user at the plugin's write floor), with size caps and a per-plugin rate limit so
    a component in a render loop cannot fill the table. An explicit report is always stored; *captured* log
    statements obey the configured threshold.
  - **Capture follows the code, not a spelling.** The core package prefix is derived from the appender's own
    package, so renaming the namespace cannot silently switch capture off. Plugins are recognised by the
    logger name the host gives them (`plugin.<pluginId>`) rather than by their package — a third-party plugin
    can live anywhere — and because the id is in the name, attribution also survives a plugin logging from its
    own thread, which a thread-local MDC would not. Backend plugins get such a logger from the SDK
    (`ctx.logger()`) when the plugin contract next bumps.
  - **The viewer is a table.** Time, level, area, source and message are fixed columns, so the message always
    starts at the same place instead of being pushed around by the length of whatever came before it. A caret
    marks the rows that can be expanded — only entries that actually carry a stack trace or structured
    context — so nobody has to click a row to find out whether there is anything under it.
  - **The application talks now.** Feed polls report item counts, what changed and how long they took;
    site settings log what changed old → new; branding, legal pages and their translations, plugin
    activation/config/purge, feed add/enable/interval/refresh, planned episodes, account creation, identity
    linking and access-token create/revoke all leave an entry. A startup summary records version, profile,
    Java, plugin contract, capture level, plugin states (naming any that are not running) and feed counts.
    Nothing per-request, and no secrets: token entries carry the prefix, never the secret, and account
    entries carry ids, not email addresses.
  - **Storing and showing are separate decisions.** The store keeps **INFO and above** (the dev profile
    lowers it to `DEBUG`), because the routine line before a failure is usually what explains it and an entry
    never stored cannot be found later. The viewer opens at **WARN and above** so nobody has to read chatter
    to find the problem — one dropdown away from everything else. The level filter means "this level *and
    above*": a view filtered to WARN that hid ERRORs would be actively misleading.
  - Retention prunes daily under ShedLock by age (30 days) and row cap (100 000); both configurable under
    `mosaicast.log.*`, as is `capture-level`.

### Fixed

- **A failing feed now shows *why*** in Admin → Feeds. `lastError` was persisted, serialised into `FeedView`
  and sent to the browser, and the page rendered only the status word — so a broken feed showed `ERROR` with
  no way to find out what went wrong.

### Added

- **Consent service (M5 E5d, `0.5.6`, ARCHITECTURE §12.5):** the platform consent mechanism, driven entirely
  by what plugins declare.
  - **The core stays banner-free.** It sets only strictly necessary and functional storage, so `GET /api/consent`
    returns an empty payload and the shell asks nothing — until an *active* plugin declares a category.
    `necessary` is never asked about, and switching a plugin off withdraws its ask again.
  - **The notice is generated, not written:** the banner names each category, the plugins that requested it and
    the third-party hosts they declared, and links the legal page marked `privacy` (§12.6). Decisions are per
    category, revocable via a **Cookie settings** entry in the footer (shown only where consent is asked for),
    and stored in `localStorage` so they work for anonymous visitors.
  - **`ctx.consent.has()` is real and denies by default** — it previously returned `true` unconditionally,
    the inverse of the SDK's own default. A plugin now gets exactly the permission the visitor granted.
  - **The declaration is also the permission:** the CSP is built per request from the declared
    `externalSources` of active plugins (`script-src`/`frame-src`/`connect-src`), so an undeclared third party
    stays blocked even if a plugin tries to load it, and a value that could break out of the header is dropped
    rather than escaped. The base policy stays `default-src 'self'`.
  - Consent declarations also surface in the admin plugin list as the §12.5 audit.

- **Plugin deep links, share metadata & sitemap (M5 E5c, `0.5.5`, ARCHITECTURE §6.4/§6.6/§7.4):** plugin
  content becomes linkable, shareable and discoverable.
  - **`/p/{pluginId}/*` is reserved:** the shell renders the plugin's slots in a new **`page`** region at site
    scope and hands the subpath below the prefix to the element as `ctx.route` — until now a stub. Only the
    addressed plugin renders there, and only if it declares a `page` slot.
  - **Server-side OpenGraph (§6.4):** link scrapers run no JS, so the host now answers `/p/{id}/…` itself with
    the shell plus injected `og:*`/`twitter:*` tags, taken from the plugin's optional `ShareMetadataProvider`
    (in the SDK since `0.3.0`, never called until now) and falling back to site-level metadata when a plugin
    has no provider or no match. The new `IndexHtmlService` is the seam M6 extends to episode/feed OG, JSON-LD
    and the no-JS content block. An unknown or switched-off plugin gets a **real 404** (§6.6, no soft-404).
  - **Dynamic `sitemap.xml` (§6.6):** episodes, feed views and legal pages, plus entries from each active
    plugin's optional `SitemapProvider`. A plugin's URLs are validated to sit under its own `/p/{id}/`
    namespace, so it cannot inject site URLs or another plugin's. Disabling a plugin removes its entries.
  - Plugin extension points are resolved through the retained PF4J manager and every call is isolated like a
    scheduled task: a provider that throws is logged and skipped, never breaking a render or the sitemap.
  - **Not yet:** `robots.txt`, the admin-configurable AI-crawler policy and JSON-LD stay M6.

- **Plugin admin UI (M5 E5c, `0.5.4`, ARCHITECTURE §7.2/§7.8):** **Admin → Plugins** becomes operable. Each
  plugin is a card with an **activation toggle**, a **generated config form** — one input per declared field,
  its kind chosen from the declared `type`, the delegated role shown next to the label, and a **Reset** that
  clears the override back to the manifest default — and a **Purge data** action behind a confirm that names
  the plugin and states what survives. A switched-off plugin is chipped `Disabled` and says plainly that it
  no longer renders, serves data or runs tasks, and that its backend stops at the next restart. Errors surface
  from problem+json rather than as a silent no-op.
  - **Not yet:** the page stays ADMIN-gated, so a podcaster cannot reach the fields the backend already
    delegates to them (`editableBy: podcaster`) — that needs the admin route opened to podcasters and is
    tracked with the rest of E5c.

- **Plugin config, activation & purge — backend (M5 E5c, `0.5.3`, ARCHITECTURE §7.2/§7.8/§8.5):** the host now
  owns per-plugin settings. Migration `V14` adds `plugin_activation` (absent row = enabled, so a fresh install
  needs no bookkeeping) and `plugin_config` (absent key = the manifest default still applies).
  - **Config persistence:** `PluginConfig` resolves **admin override → manifest default → empty**, read
    through on every call, so an edit applies without a restart. Only fields the manifest declares are
    readable, so a stale override can never surface as config.
  - **Activation (§7.8):** `PUT /api/admin/plugins/{id}/enabled`. Switching off takes effect **immediately**
    for every host-mediated surface — dropped from `GET /api/plugins/manifest` (the shell unmounts), data API
    and asset bundle 404, scheduled tasks stop firing, and the plugin's **doc-store writes are refused**, which
    also stops a thread it started itself. Its PF4J extension stays in process until the next boot, where the
    loader skips it entirely (status `DISABLED`). Full containment needs a restart either way — PF4J
    `stopPlugin` would not kill a plugin's own threads, so the host does not pretend otherwise.
  - **Purge (§7.8):** `POST /api/admin/plugins/{id}/purge` deletes every doc-store document of a plugin and
    reports the count. Activation and config survive on purpose — purging data is not a reset.
  - **Admin surface:** `GET /api/admin/plugins` now carries `enabled`, the declared `config` (type,
    `editableBy`, default, effective value, whether it is overridden) and the declared `consent` block.
    `PUT /api/admin/plugins/{id}/config` sets or clears overrides — a JSON `null` clears one; an undeclared
    field or a wrong-typed value is a 400, a field the caller's role may not edit a 403. Per §7.2 the endpoint
    is open to **PODCASTER** for fields delegated to them; activation and purge stay ADMIN.
  - **Manifest validation** now rejects a config field whose `type` is not `string`/`number`/`boolean`, whose
    `editableBy` is not `admin`/`podcaster`, or whose default contradicts its own declared type — the host
    renders and type-checks from this declaration, so it must be renderable at load time.
  - **Not yet:** the admin UI for all of this (next), deep links/SEO (E5c), consent (E5d).

- **Readable episode slugs (`0.5.2`, ARCHITECTURE §4.1):** every episode now has a stable, human-readable
  **slug** (e.g. `the-sample-cast-s01e06`) as its public identifier, while the UUID stays the internal key.
  - Minted once at creation from the feed title + season/episode (title fallback; numeric suffix on
    collision) and **immutable** — a later feed-title/number edit never re-slugs an episode, so URLs and
    plugin data never orphan. Existing episodes are backfilled at boot. Migration `V13`.
  - **Public URLs and the episode API use the slug:** `/episodes/{slug}` and `GET /api/episodes/{slug}`
    (+ `/adjacent`). `EpisodeSummary`/`EpisodeDetail` now carry both `slug` (public) and `id` (UUID, used for
    listening progress and audio).
  - **Plugins address episodes by slug:** `ctx.episodes` and the episode `scope.id` are slugs (so a plugin's
    doc-store path `data/episode/{slug}/…` matches the episode URL), and a new `ctx.episodeLabels` gives each
    a display label (`S01E06 · <title>`) so plugin pickers show titles, not ids. Backed by
    `GET /api/plugins/scope-episodes` returning `[{id, label}]`.



- **Plugin system — frontend mounting (M5 E5b, `0.5.1`, ARCHITECTURE §7.3/§7.5):** the shell now renders
  plugins.
  - **Mounting:** on load the shell fetches `GET /api/plugins/manifest`, injects each plugin's frontend bundle
    once (`/plugins/{id}/assets/{entry}`), and mounts its Web Components into the slot regions — matched by
    `placement` + scope level, gated by `visibleTo`, stacked by `order` — each in the existing error boundary
    so a throwing plugin blanks only its own tile.
  - **Context (`ctx`):** the host sets the SDK `PluginContext` on each element — resolved `scope`, host-filtered
    `episodes` (new public `GET /api/plugins/scope-episodes`), `user`, a namespaced `api` client over the
    doc-store surface, `locale` and `theme` tokens; `consent`/`filter`/`player`/`route`/`progress` are wired
    where cheap and stubbed where their mechanism lands later (E5c/E5d). Reassigning `ctx` re-renders.
  - **Admin surface:** an **Admin → Plugins** page lists every discovered plugin with its load state and shows
    a warning for any rejected plugin with its reason (`GET /api/admin/plugins`, §7.8).
  - **Not yet:** the generated config form and activation toggle (need config persistence); deep-links/SEO
    (E5c); consent (E5d).

- **Plugin system — backend loading (M5 E5a, `0.5.0`, ARCHITECTURE §7):** the host now loads PF4J plugins from
  `MOSAICAST_PLUGINS_DIR` at startup and gives each a `PluginContext`.
  - **Loading & failure isolation (§7.1/§7.8):** each plugin is a folder (`plugin.json` + backend JAR +
    `assets/`); a `MosaicastPluginManager` reads the manifest and loads the JAR. A bad manifest, an
    incompatible `platformApi` (must match host `0.3.x`), a declared relational `schema` (deferred, §7.6), or a
    thrown exception disables only that plugin — recorded as **rejected with a reason** — while the host keeps
    booting. No plugin can crash the host.
  - **`PluginContext` (§7.4):** a hard-scoped generic **doc store** over a new `plugin_data` JSONB table
    (Flyway `V12`), `PluginConfig` from the manifest, host-resolved `FeedAccess.episodesIn/display` (reusing the
    enabled-feed visibility filter), and ShedLock-wrapped `onSchedule`. `schema()` is `null` in v1.
  - **Generic HTTP surface (§7.6):** `GET/PUT/DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}` and a
    paginated list, mirroring the doc store one-to-one (there are no plugin-authored routes); reads gated by the
    plugin's `visibleTo` floor, writes by the mapped role. Plus public `GET /api/plugins/manifest` (for the shell
    to mount, E5b), `GET /api/admin/plugins` (load state, ADMIN), and `/plugins/{id}/assets/**` bundle serving
    with ETags.
  - **Not yet:** frontend mounting of plugin Web Components, config admin form and activation UI (E5b);
    SEO/deep-links (E5c); consent (E5d); relational schema provider (E5e).

### Changed

- **Consume plugin SDK `0.3.0`** (`0.4.7`): bumped `dev.mosaicast:plugin-api` / `plugin-testkit` and
  `@mosaicast/plugin-sdk` from `0.2.0` to `0.3.0` (the SDK's symmetric doc-store cut — `DocStore.delete`,
  keyed `query`, `DocStore.KEY_PATTERN`, `Scope.SITE_ID`/`Scope.site()`). The advertised `platformApi`
  version (footer "Powered by Mosaicast", `/api/meta`, player) therefore moves 0.2.0 → 0.3.0. No core
  behaviour change: core does not yet implement the plugin doc-store contracts, so the SDK's breaking
  backend changes have no effect here — M5 implements them.

### Fixed

- **Previous/next navigation follows release order** (ARCHITECTURE §6.2, spec updated to match): the detail
  page and the player's auto-advance walk the feed's `publishedAt` order — the same sequence the browsable
  list shows — instead of season/episode number. Real feeds routinely omit `itunes:episode` (e.g. Acast), so
  a numberless trailer or bonus episode used to be coerced to "episode 0" and jump to the front of the
  sequence; it is now placed by its date. An episode without a `pubDate` still counts as the series start.
- **Upcoming episodes are no longer navigation neighbours** (§6.2): `PLANNED` episodes lead the *listing* but
  have no audio, and the nav sequence reused the listing order — so the oldest release had an unreleased
  episode as its "previous", and auto-advance could land on an unplayable one. The sequence now contains only
  released episodes; a planned episode's own page links back to the latest release.
- **Disabling a feed now hides it from the public site**, not just from polling (ARCHITECTURE §5.4/§6.1). A
  disabled feed and its episodes are excluded from the feed tabs (`GET /api/feeds`), the unified episode feed,
  per-feed listings, seasons/tags, and search, and its feed/episode detail pages 404. Nothing is deleted —
  re-enabling restores everything.

### Added

- **i18n scaling, legal editor & shell polish (M4.2, `0.4.6`):**
  - **Site default language** on `SiteConfig` (Flyway `V11`, ARCHITECTURE §12.7), set in Site & branding:
    it is the **legal-page fallback** (replacing the hardcoded `en`) and the **initial UI language** when a
    visitor's browser language isn't one we ship (an explicit stored choice still wins).
  - **Dynamic language menu:** the top-bar EN⇄DE toggle becomes a dropdown built from the *registered* i18n
    locales, and the legal editor's per-language tabs come from the same set — adding a language is just a new
    `locales/*.json` + one registration line; nothing else changes.
  - **Legal editor redesign:** a collapsed page list with **Edit**, opening a **tabbed** title/markdown editor
    (one tab per UI language) instead of stacking every locale — scales past two languages.
  - **Polish:** dropdown triggers restyled (consistent height, ▾ caret, subtle open animation), the ⓘ button
    no longer taller than its neighbours, the accent picker is a styled swatch, and the footer credits
    **"Powered by Mosaicast <ver>"** linking to the GitHub repo.

- **Admin round-2 (M4.1, `0.4.5`):** operator-facing follow-ups to the E4 admin surface.
  - **Legal pages now appear** (§12.5/§12.6): the authored privacy/imprint/terms pages — reachable via the
    API but rendered nowhere — now show as **footer links** and in an always-visible **top-bar info menu**
    (reachable from every page without scrolling), with a public `/legal/:slug` route rendering the
    server-sanitized HTML. The persistent player no longer overlaps the footer. Flyway `V10` seeds factual,
    template-marked **privacy + imprint** pages (EN/DE) that document the only client storage the core sets
    (session + CSRF cookies; a few functional `localStorage` keys). All strictly necessary/functional, so the
    core stays **banner-free** (ePrivacy Art. 5(3) / DE § 25 TDDDG); transparency is provided via the notice.
  - **User management** (§8.5): `GET /api/admin/users` + `PUT /api/admin/users/{id}/role` and an
    `/admin/users` page (ADMIN). Admins can grant up to ADMIN; the server rejects changing your own role and
    demoting the last admin. Role changes take effect on the user's next request.
  - **Per-feed poll interval** (§5.4): `POST /api/admin/feeds/{id}/poll-interval` (clamped 5 min – 7 days)
    with a preset selector in the feeds admin — the interval was stored but not editable.
  - **UX fixes:** dropdown menus (account/login/info) close on outside-click, Escape, item select and route
    change (were native `<details>`); the Site & branding form now prefills the **saved** theme mode + accent
    seed (it read the site config before it loaded and never refreshed after save).

### Roadmap

- **Disabled buttons must look disabled (UI polish, next shell change):** `styles.css` has no `:disabled`
  rule at all, so a disabled `.mc-btn` is visually identical to an active one — it still shows the pointer
  cursor and full contrast. **Admin → Legal pages → "Create page"** is the reported case (disabled until a
  slug is typed, so it reads as a dead button), but the same applies everywhere the shell disables a control.
  Fix once in `.mc-btn:disabled` (reduced opacity, `cursor: not-allowed`, no hover state), not per page.

- **Consent service (§12.5):** the full category-based cookie consent (necessary / functional / analytics /
  plugin-declared, `ctx.consent`, click-to-load placeholders, generated notice) is **plugin-driven** and
  lands with **E5 (plugins)** — no banner is needed until a plugin sets non-essential cookies.

- **Admin UI (E4d, `0.4.4`, ARCHITECTURE §8.5/§12):** a role-gated admin area (`/admin`) over the existing
  M3 backends — **Site & branding** (name, theme mode, accent seed with live preview, logo/favicon/dark-logo
  upload + clear; ADMIN), **Legal pages** mini-CMS (create/edit/delete pages + per-locale markdown; ADMIN),
  and **Feeds** (list with poll state, enable/disable, refresh, add-with-preview, and confirm/dismiss of the
  fuzzy PLANNED-binding suggestions; PODCASTER+). New admin read `GET /api/admin/legal` (pages + raw
  translations for the editor).
- **Account & auth UI (E4c, `0.4.3`, ARCHITECTURE §8):** log in from the shell (Discord `oauth2Login`, plus
  a dev-login option under the `dev` profile), an account menu (avatar / name / role), and an **account page**
  — linked identities (connect / unlink, with last-identity lockout) and personal access tokens (create shows
  the secret once, revoke). Player **listening progress** now syncs server-side for logged-in users
  (`GET`/`PUT /api/me/progress`, Flyway `listening_progress`); anonymous stays local. `GET /api/meta` reports
  `devLoginEnabled`.

## [0.4.2] — 2026-07-11

### Added

- **Shell v2 — rich cards, real covers, tabs & tags (E4b+, `0.4.2`, §6):** hands-on revision of the shell.
  Feeds now surface **real cover art** (`itunes:image`, episode → feed fallback via the SDK's new
  `DisplaySnapshot.artwork()`), **author** and **subtitle**; the feed is a **one-column, cover-left card**
  (prominent cover, feed + author, title + subtitle, S·E · date · runtime, description excerpt). The home is
  **per-feed tabs** (All + one per feed; a single-feed site shows no tabs and lives at the feed's own URL,
  so bookmarks survive adding feeds). New **tag** filter (`itunes:keywords`/`<category>` → `episode_tag`,
  Flyway V7) alongside season/order; `GET /api/tags` + a `tag` param on `GET /api/episodes`. The persistent
  **player** now shows cover + feed + S·E and links to the episode. Requires SDK **0.2.0**
  (`DisplaySnapshot` gains `imageUrl`/`feedImageUrl`/`author`/`subtitle`). Spec updated: ARCHITECTURE
  §4.2/§6.1, BRIEF §E4. Host-defined **subfeeds** (saved filters) noted as a future milestone.
  - Follow-up polish: the feed view is now **two-column** — a left **scope panel** (feed cover/title/author/
    description + a `feed` plugin region on a feed tab; site logo/name + a `site` region on All) beside the
    episode list, which **infinite-scrolls** (auto-load + Load-more fallback) instead of paginating. Feed
    metadata (cover/description/author) is stored on the feed (Flyway V8) and served by `GET /api/feeds/{id}`.
    Card play button reveals on hover (and is always shown on touch).

- **React/Vite shell — foundation (E4a, M4 `0.4.x`, ARCHITECTURE §6, §12.3):** the walking-skeleton shell
  becomes the real app foundation — semantic theme tokens applied at runtime from the site payload with a
  **no-flash** pre-paint script (external, CSP-`self`-friendly), persistent **top-bar chrome** + footer,
  client-side **routing** (react-router), and a typed **API client** (cookie session + CSRF). New public
  **feed catalog** `GET /api/feeds` (slim `PublicFeedView` — no admin fields leak) drives the home index.
  Plugin **slot regions** (`top`/`card`/`main`/`sidebar`/`player`) are established as empty,
  error-boundaried mount points for E5. Feed/detail views + persistent player land in E4b.
- **Multi-arch release image:** the release workflow now builds+pushes `linux/amd64` **and** `linux/arm64`
  (QEMU + Buildx), so the host runs on Raspberry Pi / Apple Silicon / AWS Graviton.
- **React/Vite shell — unified feed, detail & player (E4b, `0.4.1`, §6):** the **unified episode feed** is
  now the centerpiece — episodes across all feeds as wide cards with generative covers, and **feed / season
  / ordering as filters** (state in the URL, §6.1); the feed catalog is a filter, not the landing list.
  Per-feed pages (`/feeds/:id`) reuse the same view scoped to one feed. **Detail page** with hero, sanitized
  show notes (DOMPurify), and fixed **previous/next** navigation (§6.2). A **persistent player** (survives
  route changes) with play/seek/volume, the **Media Session API**, **auto-advance** to the next episode, and
  listening-progress persistence to localStorage (server-side sync for logged-in users comes in E4c). New
  public reads: `GET /api/episodes` (site-scope list, `feedId`/`season`/`order` filters) and
  `GET /api/episodes/{id}/adjacent`.

- **Storage, branding & theming (M3, ARCHITECTURE §11–§12):**
  - `BlobStore` interface + `PostgresBlobStore` (BYTEA) with server-side byte-range reads and namespace
    routing (audio moves to S3 later without touching callers).
  - `SiteConfig` (single row) + public `GET /api/site` and ADMIN edit; the theme is generated from one
    accent in **OKLCH** with a **WCAG AA contrast clamp**, so no accent can produce unreadable text.
  - Branding assets served at `/branding/{logo,favicon,dark-logo}` with ETag/304 and bundled-default
    fallback; admin upload/clear; **uploads are raster-only** (SVG rejected as an XSS vector) with a size cap.
  - Legal-pages mini-CMS: admin CRUD over per-locale markdown pages (slug, title, role marker, sort order),
    a public footer list with locale fallback, and sanitized markdown rendering.
- **Auth & identity (M2, ARCHITECTURE §8):** social login (Discord) via Spring Security `oauth2Login`,
  `User` + `LinkedIdentity` keyed on `(provider, external_id)`, account-merging rules (§8.3), server-side
  sessions with CSRF, RBAC (ADMIN/PODCASTER/FAN), env-bootstrapped admin, `/api/me` + identity management
  with last-identity lockout protection, podcaster-scoped personal access tokens, and a `dev`-profile-only
  login bypass for local testing.

### Security

- Auth hardening from code review: role changes and account deletion now take effect on the **next
  request** (per-request user reload, no stale sessions); linking a provider already owned by another
  account is rejected instead of switching accounts; the §8.3 verified-email case uses the **conservative
  variant** (require explicit linking, never merge silently) with normalized email matching; the API is
  **deny-by-default** (`/api/**` denies unless explicitly allowed); `/api/admin/**` beyond feeds is
  ADMIN-only; the session cookie is `Secure` by default (off only in the `dev` profile); and personal
  access tokens throttle their "last used" writes.

### Fixed

- Code-review pass over M0–M3 (availability, correctness & spec completeness):
  - Public episode endpoints no longer 500 on a client `sort` parameter — the server owns the ordering
    (FTS rank / canonical order), so the incoming sort is stripped.
  - The feed scheduler survives a poisoned feed: any poll error backs the feed off (admin-visible) and the
    tick continues to the next feed instead of aborting.
  - Concurrent reconciliation of one feed (a scheduler tick racing "refresh now") is serialized with a
    pessimistic row lock, so the two can no longer both insert the same GUID and 500.
  - Episode list/search resolve their display snapshots in a single batch query instead of one-per-row (N+1).
  - Branding serving uses `no-cache` (a change propagates immediately via a cheap 304), honours the real
    `If-None-Match` grammar (`W/…`, lists, `*`) via Spring's `checkNotModified`, and no longer loads the
    asset bytes before a 304 or 404s on the read-after-stat race (falls back to the bundled default).
  - A failed social login now carries its reason to the shell (`/?login_error=account_conflict|link_required`)
    instead of a bare flag.

- **Fuzzy PLANNED-binding confirm flow (ARCHITECTURE §5.3):** reconciliation's fuzzy-title suggestions are
  now persisted (`binding_suggestion`, Flyway V6) instead of only logged, and the podcaster can review them
  (`GET /api/admin/feeds/{feedId}/suggestions`), confirm one (`POST …/suggestions/{id}/confirm` — binds the
  planned episode to the feed item and removes the auto-created duplicate), or dismiss it
  (`DELETE …/suggestions/{id}`). Still never auto-applied.

## [0.1.0] — 2026-07-07

First milestone: the host boots, serves the shell, and ingests RSS feeds.

### Added

- **Walking skeleton (M0):** Spring Boot 3 / Java 21 backend, React + Vite shell served from the
  backend, Flyway migrations, baseline security headers (CSP, `X-Content-Type-Options`,
  `Referrer-Policy`), RFC 7807 error handling, Actuator health, multi-stage Dockerfile, and CI.
- **Domain & feed pipeline (M1, ARCHITECTURE §4–§6):**
  - Two-layer model: `EpisodeRef` (authoritative identity — status `PLANNED`/`PUBLISHED`/`WITHDRAWN`,
    season relation, access) and `EpisodeDisplay` (non-authoritative feed snapshot).
  - Capability-driven `FeedSource` SPI with an RSS implementation (Rome + iTunes module) polling via
    HTTP conditional GET (ETag / If-Modified-Since).
  - Reconciler: the three GUID cases (create / refresh / withdraw — never hard-delete) plus PLANNED
    binding (exact season/episode auto-binds; fuzzy title only suggests).
  - ShedLock-wrapped scheduler with exponential backoff; "refresh now" endpoint.
  - Paginated public read API, full-text episode search, and admin feed / planned-episode management,
    all with `application/problem+json` errors.
- **Branding:** default Mosaicast logo + mark, used as favicon and header until custom branding lands.
- **Versioning:** `/api/meta` exposes the core version (single source: `gradle.properties`), shown in
  the shell alongside the plugin SDK version.
- **i18n:** English (source) + German, with an anonymous language switcher.

[Unreleased]: https://github.com/Mosaicast/mosaicast-core/compare/v0.4.2...HEAD
[0.4.2]: https://github.com/Mosaicast/mosaicast-core/compare/v0.1.0...v0.4.2
[0.1.0]: https://github.com/Mosaicast/mosaicast-core/releases/tag/v0.1.0
