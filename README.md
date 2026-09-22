<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/mosaicast-logo-dark.svg" />
    <img alt="Mosaicast" src="assets/mosaicast-logo.svg" width="360" />
  </picture>
</p>

<h1 align="center">mosaicast-core</h1>

> The host: Spring Boot backend + React/Vite shell. Loads plugins, unifies feeds, manages auth/branding/theming.

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

## What is this?
See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Screenshots

The unified episode feed (episodes are the centerpiece; feed / season / ordering are filters). The shell
themes from a single accent seed (OKLCH + WCAG clamp) — shown here in light and dark:

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/screenshots/home-dark.png" />
    <img alt="Mosaicast shell — unified episode feed" src="assets/screenshots/home-light.png" width="720" />
  </picture>
</p>

The episode detail page — hero with play, sanitized show notes, and fixed previous/next navigation:

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/screenshots/detail-dark.png" />
    <img alt="Mosaicast shell — episode detail" src="assets/screenshots/detail-light.png" width="720" />
  </picture>
</p>

The account page — linked identities and personal access tokens:

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/screenshots/account-dark.png" />
    <img alt="Mosaicast shell — account" src="assets/screenshots/account-light.png" width="720" />
  </picture>
</p>

The admin area — site name, theme mode, accent seed with live preview, and branding upload (Legal pages and
Feeds live behind the same role-gated nav):

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/screenshots/admin-dark.png" />
    <img alt="Mosaicast shell — admin site & branding" src="assets/screenshots/admin-light.png" width="720" />
  </picture>
</p>

<sub>Screenshots use a fictional sample feed (`assets/sample/`), not any real podcast, and are captured
from the running shell during UI work — see the note in `CLAUDE.md`.</sub>

## Prerequisite: the plugin SDK

Core builds against the published **`@mosaicast/plugin-sdk`** — the TypeScript package on **npm** (public)
and the Java artifacts (`dev.mosaicast:plugin-api` / `plugin-testkit`) in **GitHub Packages**. Pin the
version in `gradle/libs.versions.toml` and `frontend/package.json` (currently `0.12.0`).

- **Frontend:** resolves from public npm — nothing extra, just `npm install`.
- **Backend:** GitHub Packages requires authentication even for reads. Either
  - set `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties` (a PAT with `read:packages`), or export
    `GITHUB_ACTOR` / `GITHUB_TOKEN`. Note that a `gh auth token` does **not** carry `read:packages` by
    default, so borrowing one gets you a `401 Unauthorized` from Gradle rather than a clear message; **or**
  - for offline/local work, publish the SDK to your Maven Local from the sibling repo
    (`../mosaicast-plugin-sdk`): `./gradlew publishToMavenLocal`, then build with `-PuseMavenLocal` (or set
    `useMavenLocal=true` in `~/.gradle/gradle.properties`) — no token is needed. It is opt-in because
    `mavenLocal()` outranks everything else: with it always on, a stale artifact in `~/.m2` silently beats
    the version the catalog pins, and the build says nothing about it.

## A disposable dev instance

`dev/instance.sh` stands up a throwaway Mosaicast — its own Postgres on :5433 and the app on :8081, so it
never touches a normal dev setup — seeded only with the fictional sample feed.

```bash
dev/instance.sh up --admin      # + --plugins to load ./plugins
dev/instance.sh up --audio ~/podcasts   # make the seeded episodes actually playable
dev/instance.sh status          # up? how many plugins?
dev/instance.sh logs -f
dev/instance.sh psql            # a shell on the fleeting database
dev/instance.sh down
```

Use it to check a change by hand, exercise an admin flow, point a plugin at a real host, or refresh the
README screenshots.

The sample feed's enclosures point at `example.com`, so pressing play does nothing — which is fine until
the thing you are checking *is* playback. `--audio DIR` repoints them at your own audio files (matched to
episodes in sorted order, oldest file to oldest episode) in a staged copy of the feed; the checked-in file
is never modified and nothing is copied into the repo. It also switches the CSP to strict media sources so
loopback audio is allowed, which narrows `img-src` too — so it is not the mode to take screenshots in.

## Build & test

```bash
./gradlew build            # backend: compile + unit + Testcontainers integration tests (needs Docker)

cd frontend
npm ci                     # installs @mosaicast/plugin-sdk from public npm
npm test                   # Vitest component tests
npm run build              # builds the shell into ../src/main/resources/static
```

> **Testcontainers + Docker Engine 29+:** the build pins the docker-java client API version (`api.version`
> system property, default `1.44`) and disables Ryuk in CI sandboxes. Override via `DOCKER_API_VERSION` /
> `TESTCONTAINERS_RYUK_DISABLED` if your daemon needs different values.

## Run locally

```bash
# 1) build the shell into static resources (see above): cd frontend && npm run build
# 2) start Postgres + the app:
cp .env.example .env              # fill in (DB password, Discord OAuth, bootstrap admin)
docker compose up --build         # → http://localhost:8080
# …or run the backend directly against a local Postgres:
./gradlew bootRun
```

### Dev profile & dev-login (local only)

`--spring.profiles.active=dev` enables developer conveniences, including (from M2) a **dev-login bypass**
that mints a session for any role **without Discord**. It exists **only** under the `dev` profile and is
structurally absent in production — never enable it on a deployed instance. The real Discord OAuth flow
needs real `DISCORD_CLIENT_ID`/`SECRET` and gets a one-time manual browser test at deployment.

The **Dockerfile** is multi-stage (Vite build → Gradle `bootJar` → slim JRE, shell baked in). The frontend
resolves the SDK from public npm; the backend needs a GitHub Packages token passed as a BuildKit secret —
see the header of `Dockerfile` for the `docker buildx build --secret …` invocation.
Plugins folder via `MOSAICAST_PLUGINS_DIR` (in the container `/app/plugins`, volume `./plugins`);
languages folder via `MOSAICAST_LOCALES_DIR` (`/app/locales`, volume `./locales`) — see **Languages** below.
Layout reference for the shell: `docs/reference/mosaicast-mockup.jsx` (NOT the real architecture).

## Versioning & releases

- **Single source of truth:** the core version lives in **`gradle.properties`** (`version=…`). It is
  filtered into `build-metadata.properties` at build time, served at **`GET /api/meta`**, and shown in
  the shell next to the plugin SDK version. Nothing else sets it.
- **Scheme:** the minor version tracks the build milestone — **M1 = `0.1.x`, M2 = `0.2.x`**, … — and is
  independent of the plugin-contract (SDK) version. See [`CHANGELOG.md`](CHANGELOG.md).
- **Cutting a release:** bump `gradle.properties`, update `CHANGELOG.md`, then tag `vX.Y.Z` and publish a
  GitHub Release. `.github/workflows/release.yml` builds the image and pushes it to
  **`ghcr.io/mosaicast/mosaicast-core:X.Y.Z`** and `:latest` (the image's `/api/meta` version is stamped
  from the tag).

### Dev vs. production compose

- **Dev** — `docker-compose.yml` **builds** the image locally (needs the GitHub Packages token, above):
  ```bash
  docker compose up --build
  ```
- **Production** — `docker-compose.prod.yml` **pulls** the released image from GHCR (no build, no token):
  ```bash
  MOSAICAST_VERSION=0.1.0 docker compose -f docker-compose.prod.yml up -d   # omit to run :latest
  ```

## Plugins (E5)

The host loads plugins from **`MOSAICAST_PLUGINS_DIR`** (default `./plugins`) once at startup — no hot reload.
Each plugin is one folder holding a `plugin.json` manifest, a backend JAR (a PF4J extension of
`dev.mosaicast.plugin.api.PluginBackend`), and an `assets/` bundle, e.g.:

```
plugins/sample/
  plugin.json
  sample.jar          # the PF4J @Extension backend
  assets/sample.es.js # the frontend Web Component bundle
```

At boot the host validates each manifest (the declared `platformApi` must match the host's `0.9.x`; config
fields must be renderable), loads the JAR, and calls `register(ctx)`. A bad manifest, an incompatible
`platformApi`, an unusable `schema` declaration, or a thrown exception
**disables only that plugin** — it is recorded as rejected while the host keeps booting (ARCHITECTURE §7.8).
A plugin an admin switched off is skipped here entirely.

The frontend never calls plugin-authored routes; there are none. Instead the host exposes fixed, generic,
per-plugin surfaces the plugin's Web Component reaches via `ctx.api` (the doc store) and `ctx.schema` (its
declared tables, read-only):

```
GET    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}      # one doc; 404 if absent
GET    /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=   # paginated list
PUT    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}      # upsert (last-write-wins)
DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}      # idempotent
GET    /api/plugins/{id}/schema/{entity}?where=&orderBy=&page=&size=      # declared rows, filtered
GET    /api/plugins/{id}/schema/{entity}/search?field=&q=&where=&page=&size=  # full-text, on the GIN index
GET    /api/plugins/{id}/schema/{entity}/count?where=          # how many match
GET    /api/plugins/{id}/schema/{entity}/{rowId}               # one row; 404 if absent
GET    /api/plugins/{id}/episodes?slugs=a,b,c                  # display snapshots, host-filtered (ctx.feeds)
GET    /api/plugins/{id}/tags                                  # the site vocabulary + reach (ctx.tags)
GET    /api/plugins/{id}/tags/{tag}/episodes|subjects|similar  # who carries a tag; what co-occurs with it
GET    /api/plugins/{id}/episodes/{slug}/tags                  # the tags on one episode
GET    /api/plugins/{id}/subjects/{subjectKey}/tags            # the tags on one of the plugin's subjects
PUT    /api/plugins/{id}/tags/{tag}/subjects/{subjectKey}      # tag your own subject (writableBy)
DELETE /api/plugins/{id}/tags/{tag}/subjects/{subjectKey}      # idempotent; the vocabulary entry stays
PUT    /api/plugins/{id}/tags/{tag}/episodes/{slug}            # needs `tags.writesEpisodes`; else 403
DELETE /api/plugins/{id}/tags/{tag}/episodes/{slug}            # removes only this plugin's own row
GET    /api/plugins/manifest                                   # public: active plugins' frontend + slots
GET    /plugins/{id}/assets/**                                 # the plugin's frontend bundle (ETagged)
GET    /p/{id}/**                                              # public: the plugin's deep-link page (+ OG tags);
                                                               # 404 when the plugin's PageRouteProvider says
                                                               # it renders nothing there (absent ⇒ 200)
GET    /api/consent                                            # public: services, storage and the fingerprint
GET    /api/admin/consent                                      # ADMIN: the same, attributed to plugins, + the CSP
GET    /api/admin/plugins                                      # ADMIN: discovered plugins, state, config, consent
PUT    /api/admin/plugins/{id}/enabled?value=                  # ADMIN: activation toggle
PUT    /api/admin/plugins/{id}/config                          # ADMIN + PODCASTER (per field `editableBy`)
POST   /api/admin/plugins/{id}/purge                           # ADMIN: delete the plugin's stored documents
```

Access is what the manifest **declares** — `"data": { "readableBy", "writableBy" }` — not what the slots
imply; `visibleTo` governs rendering only. Saying nothing gets the closed answer (reads default to the write
floor, writes to `podcaster`). Two things narrow the floors further: the `user/me` scope is resolved
server-side, so another person's partition is unnameable rather than merely forbidden; and
`"data": { "backendOwned": ["stats", "agg:*"] }` reserves the keys a plugin's backend authors — clients read
them, a client `PUT`/`DELETE` is a 403 with its own problem type. Data is hard-scoped by plugin id — a plugin
can never see another's.

The tag surface adds one declaration on top of those floors: `"tags": { "readsVocabulary", "writesEpisodes" }`.
Absent means **no tag surface at all** — `ctx.tags` is `null` and the endpoints 404, the same shape as
`blobs` and `schema`. Tagging a plugin's own subjects needs only the write floor; tagging an **episode**
changes the shell's filter options and what core recommends beside that episode, so it is a separate,
declared capability. `/api/plugins/{id}/episodes` is the one plugin surface with no floor of its own: it
returns host data the same visitor can read from `/api/episodes/*`, filtered to what they may see.

### Installing a plugin

```bash
scripts/install-plugin.sh Mosaicast/mosaicast-plugin-wiki@v1.0.0#sha256:abc123…
```

| Spec | Resolves to |
| --- | --- |
| `owner/repo` | the latest release's `plugin.tgz` |
| `owner/repo@v1.2.3` | that release |
| `owner/repo@v1.2.3#sha256:…` | that release, contents verified |
| `https://…/plugin.tgz[#sha256:…]` | a tarball anywhere |
| `./plugin.tgz` | a local file (air-gapped, or one you just built) |

With no release tarball, the script clones the repo and runs its own `build.sh` instead. The folder name
comes from the manifest's `id`, never guessed from the repo name. Plugins are read **at startup only**, so
restart the host afterwards. Building by hand and dropping `dist/` into `MOSAICAST_PLUGINS_DIR/<id>/` still
works and always will.

**In Docker**, set `MOSAICAST_PLUGINS` and the entrypoint resolves it before the JVM starts:

```yaml
MOSAICAST_PLUGINS: "Mosaicast/mosaicast-plugin-wiki@v1.0.0#sha256:abc123…"
```

Restarts are idempotent — an already-installed spec is skipped without re-downloading — and an
**unresolvable spec fails the container** rather than booting without a plugin you asked for. The runtime
image resolves prebuilt tarballs only; it has no git or JDK, and says so rather than failing obscurely.

> **Pin a tag *and* a checksum.** A plugin is trusted, in-process, unsandboxed code (ARCHITECTURE §7.1) —
> the installer does not change that trust model, it just makes acting on it one env var away. An unpinned
> spec means whatever that repo publishes next runs inside your server. The checksum is the only integrity
> control this model offers, which is why `dev/templates/release-plugin.yml` publishes the digest into every
> release's notes: copy the line it prints.

**Moving plugin files between backends.** Routing a namespace at a different backend does nothing to what
is already stored — the old backend keeps the bytes, the new one starts empty, so every existing ref 404s
and the plugin's quota reads as zero while the old data still occupies the source. `migrateBlobs` is the
other half of that switch:

```bash
# 1. app stopped, routing still pointing at the source
./gradlew migrateBlobs --args="--from=postgres --to=filesystem --namespace=plugin --dry-run"
./gradlew migrateBlobs --args="--from=postgres --to=filesystem --namespace=plugin"

# on a server, the same class out of the image that is already there:
java -cp app.jar -Dloader.main=dev.mosaicast.tools.blob.BlobMigratorApplication \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --from=postgres --to=filesystem --namespace=plugin

# 2. flip mosaicast.blobs.namespaces.plugin to `filesystem`, restart, look at the app
# 3. once you believe it, reclaim the space with --delete-source
```

It is a **separate entry point, not a switch on the running app** — it wants the app stopped, takes a
while and deletes things — but it runs the *same* `BlobStore` implementations the app stores blobs with, so
a new backend is migratable the day it implements the interface and no format is described twice. The
context it boots is the blob package alone: no web server, no plugin loader, and Flyway excluded, because
schema migration is the app's decision and not a side effect of moving files.

`--from`/`--to` name backends (the same names a `namespaces` rule uses), so this grows an S3 option when an
S3 backend exists. Ids are preserved — a plugin stores the ref, so a copy that renumbered objects would
orphan every reference it ever saved. Every object is verified by SHA-256 before anything is deleted,
re-running skips what already matches, and `--dry-run` prints the plan. **`branding` is refused**:
`site_config.*_asset_id` are foreign keys into the Postgres `blob` table, so moving it would produce an
install that will not start.

**Publishing a plugin:** copy [`dev/templates/release-plugin.yml`](dev/templates/release-plugin.yml) into
the plugin repo as `.github/workflows/release.yml`. On a published release it builds, attaches `plugin.tgz`,
checks the tag against the manifest version, and appends the SHA-256 to the notes. No registry is involved —
GitHub Releases are the index.

**Frontend (E5b):** the shell fetches the manifest, injects each plugin's bundle once, and mounts its Web
Components into the slot regions — matched by `placement` + scope, gated by `visibleTo`, stacked by `order`,
each in an error boundary. The host sets the SDK `PluginContext` on the element (scope, host-resolved
`episodes`, `user`, a namespaced `api` client, `locale`, `theme`, `consent`, and `route` on a deep-link page).

### Theming a plugin: the `--mc-*` tokens

The shell's design tokens are a **contract**, not private styling. CSS custom properties inherit across a
shadow boundary, so a plugin's Web Component can read every token below without asking for it — style
against them and your UI follows the site into dark mode, into an admin's accent, and into the visitor's
reduced-motion setting. Hard-coded colours do not.

| Group | Tokens |
|---|---|
| Colour | `--mc-bg` `--mc-surface` `--mc-text` `--mc-text-muted` `--mc-accent` `--mc-accent-contrast` `--mc-accent-2` `--mc-border` |
| Surface & state | `--mc-surface-2` `--mc-border-strong` `--mc-hover` `--mc-active` `--mc-accent-soft` `--mc-overlay` `--mc-focus` (+ `--mc-focus-width`, `--mc-focus-offset`) |
| Feedback | `--mc-danger` `--mc-danger-contrast` `--mc-success` `--mc-warning` |
| Space | `--mc-space-1` … `--mc-space-8` (0.25rem → 4rem) |
| Radius | `--mc-radius-sm` `--mc-radius-md` `--mc-radius-lg` `--mc-radius-pill` |
| Elevation | `--mc-shadow-1` `--mc-shadow-2` `--mc-shadow-3` |
| Type | `--mc-font-sans` `--mc-font-mono` `--mc-text-xs` … `--mc-text-3xl` `--mc-leading-tight` `--mc-leading-normal` `--mc-weight-medium` `--mc-weight-bold` |
| Motion | `--mc-motion-fast` `--mc-motion-base` `--mc-ease` |
| Layout | `--mc-container` `--mc-container-narrow` `--mc-player-height` |

Only the eight **colour** tokens also arrive as a JS object (`ctx.theme`, the SDK's `ThemeTokens`); the SDK's
`defineMosaicastElement` writes those onto your `:host`. Everything else is CSS-only — read it with `var()`,
not from `ctx`. The definitions, and the reasoning for which values are derived, live in
`frontend/src/styles/tokens.css`.

```css
:host {
  padding: var(--mc-space-4);
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  background: var(--mc-surface);
  box-shadow: var(--mc-shadow-1);
  transition: box-shadow var(--mc-motion-fast) var(--mc-ease);
}
```

### Relational storage for plugins (`ctx.schema()` / `ctx.schema`)

The doc store is the default and covers nearly everything. A plugin that needs full-text search, revisions
or backlinks declares a **schema** instead (ARCHITECTURE §7.6), and the platform provisions namespaced
tables for it:

```json
"storage": { "schema": { "page": {
    "slug": "string:indexed:unique", "title": "string",
    "markdown": "text:fulltext", "updatedAt": "timestamp:indexed" } } }
```

Types: `string` `text` `integer` `number` `boolean` `timestamp`. Modifiers: `:indexed` `:unique`
`:fulltext` (the last only on text). Entities land in `plugin_<id>_<entity>` with a platform-assigned
`id`; the plugin addresses them by **declared entity and field name** through `ctx.schema()` and never
writes DDL or names a table.

**That is the scoping guarantee**: reaching another plugin's tables isn't blocked so much as inexpressible.
The host resolves every name against that plugin's own manifest, builds the statement itself, and binds
every value as a JDBC parameter. An undeclared entity or field throws `IllegalArgumentException` — the same
way it does against the SDK's `FakeSchemaStore`, so a manifest that drifted from the code fails in the
plugin's own tests.

Provisioning is **additive**: a new field is added on the next boot, a field dropped from the manifest
leaves its column alone, and a field whose declared type changed **refuses the plugin at load** rather than
retyping a column that already holds data. Flyway is static, so this runs through the host's own migration
runner with a bookkeeping table (`plugin_schema_table`) — which is also how **purge** knows what to drop.
Purge removes both storage kinds; removing a plugin folder still just makes it dormant (§7.8).

**The frontend reads the same tables over HTTP** (`ctx.schema`, `null` for a doc-store plugin), through the
`/api/plugins/{id}/schema/**` endpoints above: `where=field:op:value` and `orderBy=field:asc|desc` terms,
paged like every other list endpoint (`page` from 0, `size` 50, capped at 200). Values are read against the
field's *declared* type, so `views:gte:30` binds a number and `published:eq:yes` is a 400 rather than a
quietly wrong answer. The manifest's `readableBy` governs it, exactly as it governs the doc surface.

**Reads only.** A v1 plugin authors no HTTP routes, so no plugin code runs at request time to enforce slug
uniqueness or append a revision atomically — the backend stays the only writer of relational truth. A
frontend that must write puts a document in the doc store and the backend ingests it on its schedule, which
makes such a write eventually consistent.

### Configuring, switching off and purging a plugin (E5c)

**Admin → Plugins** lists every discovered plugin with its load state and, per plugin:

- a **config form generated from the manifest** — one input per declared field, its kind from the declared
  `type` (`string`, `number`, `boolean`), and **Reset** to drop back to the manifest default. Plugins never
  ship a config UI; a field the host cannot render is rejected at load. `editableBy` (`admin` | `podcaster`)
  decides who may set a field — the backend enforces it per field.
- an **activation toggle**. Switching a plugin off takes effect at once for everything the host mediates: it
  leaves `/api/plugins/manifest` (the shell unmounts it), its data API, assets and deep links 404, its
  scheduled tasks stop firing, and its doc-store **writes are refused** — which also stops a thread the plugin
  started itself. Its PF4J extension stays in process until the next restart, where the loader skips it
  entirely. Full containment needs that restart in any design (PF4J's own `stopPlugin` would not kill a
  plugin's threads either), and the UI says so rather than implying a kill switch.
- **Purge data** — deletes everything the plugin stored in the doc store. Deleting a plugin folder only makes
  it dormant; its data survives until this explicit action (§7.8). Configuration and on/off state are kept.

### Logs & health (Admin → Logs & health)

Everything the host logs about itself is also readable in the admin UI, so diagnosing a problem does not
require access to the container. A Logback appender captures core's own statements into the `app_log` table;
entries carry the area they came from, the plugin or feed they concern, and the stack trace where there is one.
Filter by level, area, plugin or text; expand a row for detail.

**Storing and showing are separate settings.** The store keeps **INFO and above** — the line before a failure
is usually what explains it — and the dev profile lowers that to `DEBUG`. The viewer *opens* at **WARN and
above** so routine chatter is not in the way, one dropdown from the rest. A level filter always means "that
level and above", so a view set to WARN never hides an error. Change what is stored with
`mosaicast.log.capture-level`.

The health card at the top answers *is anything broken right now?* — each plugin's state **with the reason it
was rejected**, each feed's poll state **with its last error**, and error/warning counts for the last 24 hours.

Plugins report their own trouble via `POST /api/plugins/{id}/log` (signed-in user at the plugin's write floor,
size-capped and rate-limited). Tune retention and capture with `mosaicast.log.*` — see `application.yml`.

### Rate limiting

Login attempts, token minting and branding uploads are rate-limited per client (ARCHITECTURE §13). Two
budgets, because the two abuses differ: **auth** defaults to 20 per minute (a flood is credential stuffing),
**uploads** to 10 per minute (a flood is a disk problem). Only state-changing requests count — a `GET` is
never throttled — and a refusal is an RFC 7807 `429` carrying `Retry-After`. Tune or disable with
`mosaicast.rate-limit.*` in `application.yml`.

It runs as the first filter in the chain, so the OAuth2 endpoints (which Spring Security handles, not a
controller) are covered and a flood is refused before it costs a session lookup.

**Know what it is.** This is a cost control, not a DoS control. Callers are bucketed by the address the
deployment resolves them to, which on a directly exposed port is caller-supplied — see *Known residuals* in
[`SECURITY.md`](SECURITY.md) for why that trade beats the alternative, and run behind a proxy that overwrites
`X-Forwarded-For` if you need a real bound. Counters are per instance until Redis lands in v3.

### Related episodes

The detail sidebar suggests what else to listen to (ARCHITECTURE §6.3). **Deliberately not the same thing as
previous/next**, which is core navigation, always shown, and lives at the foot of the page: related answers
"what else is like this", and is allowed to come back empty.

**Podcaster-curated pins win** and are shown first, in the order they were pinned. Everything after them is
computed from three signals — same season (weighted by how near in episode number), shared tags, and fuzzy
title — with same-feed as a tie-breaker rather than a qualifier. At least one real signal has to fire, so a
show's whole back catalogue does not count as "related" to each of its episodes; when nothing qualifies the
widget renders nothing rather than an empty heading.

Curation is inline on the episode page for PODCASTER and ADMIN — the judgement is about that episode and is
made while looking at it. Pins take effect immediately.

`RelatedProvider` is a swappable strategy the host resolves, **not a plugin**, so the sidebar works with zero
plugins installed. §6.3's v2 successor (an embedding strategy over `pgvector`, or a recommender) replaces
`DefaultRelatedProvider` without the endpoint or the widget changing.

### Deep links, sharing and SEO

The shell is an SPA, but link scrapers and most AI crawlers run no JS, so the server answers navigation URLs
itself rather than handing out the bare bundle.

**The host's own routes** — `/`, `/feeds/{slug}`, `/episodes/{slug}`, `/legal/{slug}` — are served by
`ShellController` with OpenGraph/Twitter tags, JSON-LD (`PodcastSeries` on site and feed pages,
`PodcastEpisode` on an episode), a `rel=canonical`, and a plain-HTML content block for crawlers that render
nothing. The content block sits inside `#root`, so React replaces it on mount. Filters are part of a view's
identity (`?season=2&tag=…`), so a shared filtered link previews as that view and canonicalizes to a
normalized form of the same filters — parameter order and the default `order=newest` do not mint extra URLs.
An unknown slug is a **real 404**, not a 200 carrying site metadata.

**Plugin deep links** — the host reserves **`/p/{pluginId}/*`**. A plugin that declares a slot at the `page`
placement renders there at site scope and receives the subpath as `ctx.route`, which is what makes plugin
content linkable; its tags come from the optional `ShareMetadataProvider`, falling back to site metadata, and
an unknown or switched-off plugin gets a real 404 too.

`GET /sitemap.xml` lists episodes, feed views and legal pages plus each active plugin's `SitemapProvider`
entries, validated to sit under that plugin's own `/p/{id}/` namespace. `GET /robots.txt` disallows the
admin/API/actuator paths and points at it. Every absolute URL the host states about itself — sitemap
`<loc>`, canonical, `og:url`, the robots sitemap reference — comes from `mosaicast.base-url`, never from the
request, so `X-Forwarded-Host` cannot reassign the site's identity to somebody else.

**AI crawlers are the operator's decision, and Mosaicast ships the mechanism rather than an opinion.**
**Admin → SEO & crawlers** offers three policies: *allow* (the default — say nothing, treat them like any
other crawler), *block* (disallow every agent in core's catalog), or *custom* (tick them individually).
The catalog names the agents by operator and purpose — training, search, and user-triggered retrieval are
separate crawlers and separate decisions — and `custom` accepts any name, so an agent that did not exist at
release can still be blocked. Worth being clear about: a `robots.txt` rule is a request. Well-behaved
crawlers honour it; enforcement against one that does not is a network-layer problem, not a setting.

Still open from §6.6: the `hreflang` and RSS-discovery tags — see the CHANGELOG for why both are decisions
rather than work.

### Consent (E5d)

The core stores only what the requested service needs — session, CSRF token, language, cached branding, the
consent decision itself — so **it runs banner-free**. A visitor is asked something only because a plugin
declared a third-party service in `consent.services[]`: a name, the **company** operating it, a category, a
privacy URL, the origins it is contacted on, whether data leaves the EU/EEA, and every item it stores with a
purpose and a lifetime.

What visitors see is generated from those declarations and **never mentions plugins** — they decide about
services and companies, not about the site's architecture. Allow and refuse carry identical weight on the
first layer. The host knows `necessary` (never asked about), `functional` and `analytics`; any other string
passes through as a plugin-declared category, shown under its own name.

**The settings are one component in three places** — `/cookies`, appended below the legal page marked
`privacy`, and inside the banner — because withdrawal has to be as easy as granting. The footer link is
unconditional: even with no plugin installed, the core's own storage is disclosed there and the
playback-position switch lives there. Decisions are per category, stored in `localStorage` as a receipt the
visitor can read and export (`decidedAt`, the declaration fingerprint, the per-category answer), and reach
plugins as `ctx.consent.has(category)`, which **denies by default**.

A stored answer stops counting when the **declaration changes** (the server's fingerprint moves, so a newly
installed service cannot inherit consent given before it existed), after **twelve months**, or when the
browser sends **Global Privacy Control** — which is honoured as a refusal without showing a banner, and can
still be overridden in the settings. Nothing about who consented is stored server-side; **Admin → Consent**
shows the operator's half instead: every declared service attributed to its plugin, the resulting CSP
allow-list, and the fingerprint.

### What is enforced, and what is trusted

The same declaration is the permission: the CSP is widened by exactly the declared `hosts`
(`script-src`/`frame-src`/`connect-src`) of active plugins, **and only for the categories this visitor
granted** — the decision is mirrored into an `mc_consent` cookie so the server can narrow the policy per
request (responses carry `Vary: Cookie` when anything is gated). An undeclared third party stays blocked even
with consent given; a declared one stays blocked until consent is given; switching a plugin off narrows the
policy again.

That distinction matters, because **`ctx.consent.has()` is advisory**. A plugin bundle is imported into the
page's own JavaScript realm — shadow DOM encapsulates styles and markup, never capabilities — so a plugin can
reach `localStorage`, `document.cookie` and `fetch` exactly as the shell can, and nothing in that realm can
take those away. What the browser refuses to connect to is not advisory, which is why the CSP, not the
contract, is where a refusal is actually enforced.

The same reasoning applies to storage, with the verb reversed. A plugin cannot be *stopped* from writing —
patching `localStorage.setItem` is one same-origin iframe away from being bypassed — but it can be undone:
after every decision, and on load, the shell deletes everything on the device that core did not declare, that
no `necessary` service declared, and that this visitor did not grant. Deleting needs no cooperation from
whoever wrote the key, because the shell owns the origin too. So a withdrawal takes the data with it rather
than only closing the tap, and an undeclared key survives no longer than the next decision or the next page
load — the sweep is periodic in that sense, not an interception of the write itself.

**Installing a plugin is a trust decision**, in the same sense as a WordPress plugin and unlike a browser
extension. What core guarantees:

| | |
|---|---|
| Enforced (server) | doc-store access hard-scoped by plugin id, declared role floors, backend-owned keys, activation gating, asset routes, log rate limits |
| Enforced (browser) | connections to origins that are undeclared **or** declared under a category the visitor refused |
| Enforced (after the fact) | device storage: undeclared or withdrawn keys are swept from `localStorage`, `sessionStorage` and script-visible cookies |
| Not enforced | the moment of the write itself; `HttpOnly` cookies set by a plugin backend; IndexedDB; and, by default, image/media requests — `img-src … https:` stays open because episode artwork comes from arbitrary feed hosts. `mosaicast.security.strict-media-sources` narrows both to the origins the site's content actually references plus the consented plugin hosts |

Under the `dev` profile the shell also warns in the console when anything writes a storage key that no
manifest declared, naming the plugin where the stack allows — detection while developing a plugin, so an
author learns *why* their key keeps vanishing. Declaring it in `consent.services[].storage` is the fix, and
that declaration is also what the visitor is shown.

**Feeds and episodes are both addressed by a public slug** (§4.1) — stable, human-readable ids
(`the-sample-cast`, `the-sample-cast-s01e06`) used in `/feeds/{slug}` and `/episodes/{slug}`, in the API, and,
for plugins, in `ctx.episodes` and the `feed` / `season` / `episode` `scope.id` (so a plugin's
`data/feed/{slug}/…` matches the URL). The UUID stays the internal key (listening progress, audio, episode
filters). Slugs are minted **once at creation and never change**, because a feed title moves on any poll and
re-slugging would break shared links and orphan plugin data. Feed URLs were UUID-shaped before `0.5.12`, so
the API still resolves a feed UUID; existing plugin documents are moved to the new scope ids at boot.
Plugins get `ctx.episodeLabels` (`S01E06 · <title>`) so pickers show titles, not ids.

## Project layout

```
src/main/java/dev/mosaicast/core/
  episode/   EpisodeRef identity + display snapshot, read/search API (ARCHITECTURE §4, §6)
  feed/      FeedSource SPI, RssFeedSource, reconciler, ShedLock scheduler, admin API (§5)
  auth/      User + LinkedIdentity, Discord oauth2Login, merging rules, /api/me, PATs (§8)
  plugin/    PF4J loading, PluginContext (doc store / feeds / config / onSchedule), /api/plugins surface (§7)
  config/    security (oauth2/session/CSRF/RBAC), headers, scheduling/ShedLock
  web/       SPA serving, RFC 7807 handling, pagination envelope
src/main/resources/
  db/migration/   Flyway migrations (core's schema is Flyway-only; plugin tables are provisioned
                  programmatically — see "Relational storage" below)
  branding/       default logo/mark fallback (§12)
  static/         built shell bundle (generated by the frontend build; gitignored)
test-fixtures/sample-plugin/   a tiny real plugin JAR, compiled for the plugin-loading integration test
frontend/    React/Vite host shell (built into resources/static)
  src/styles.css   the @import manifest; every rule lives in src/styles/*.css
  src/styles/tokens.css   the --mc-* design tokens plugins inherit (§12.3)
  dev/icons.txt           the icon whitelist -> `npm run icons` (see "Icons" below)
  dev/attributions.mjs    what we are built on -> `npm run attributions`
```

### Icons

Icons are generated, never pasted in. Add a line to `frontend/dev/icons.txt`, run `npm run icons`, and
commit the two generated artefacts; CI regenerates and diffs, so a whitelist edit that was never
regenerated fails the PR.

The tier marker decides where an icon lands, and the two are different capabilities:

| | emitted to | usable as |
| --- | --- | --- |
| `+` | `src/styles/icons.css` | `mask-image: var(--mc-icon-x); background: currentColor` — from core CSS *and* from plugins |
| `*` | also `src/components/Icon.tsx` | additionally `<Icon name="x" />` |

**Default to `+`.** Reserve `*` for what a mask cannot do: a name that varies at runtime, an icon that
carries meaning and so needs an `aria-label` (pseudo-elements are invisible to assistive tech), or
standalone markup. The published `--mc-icon-*` names are a contract with every installed plugin —
add freely, rename never — and they cross the shadow boundary exactly as the colour tokens do, so a
plugin picks up a new icon with no SDK change and no `platformApi` bump.

### About page and credits

`/about` is shipped, not admin-authored: the operator's own blurb, then what Mosaicast is (with this
build's version and a link to the source), then the plugins this install runs, then what the project is
built on. The blurb leads because someone arrived at *this podcast's site*, not at a piece of software.
Each section is absent-tolerant, so a bare install still answers "what is this site?".

The operator blurb is the `about` entry in the legal mini-CMS (**Admin → Legal pages**), which is why it
gets per-locale markdown for free; the `about` role marker keeps it out of the footer's legal group. The
credits come from `frontend/dev/attributions.mjs` via `npm run attributions`, which writes both the page
and the index in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) so the two cannot drift.

Plugins may declare `license`, `author`, `homepage` and `attribution` in their manifest; all are optional,
never validated, and shown on this page.

Key API: `POST /api/admin/feeds` (add + preview + refresh, **PODCASTER/ADMIN**),
`GET /api/admin/feeds/{id}/suggestions` + `POST …/suggestions/{id}/confirm` + `DELETE …/suggestions/{id}`
(review/confirm/dismiss fuzzy PLANNED bindings, §5.3, **PODCASTER/ADMIN**),
`GET /api/feeds` (public catalog), `GET /api/feeds/{slug}` (feed detail for the panel),
`GET /api/episodes?feedId=&season=&tag=&order=` (unified site-scope feed),
`GET /api/tags?feedId=` (tag filter options: `{ tag, label }`), `GET /api/feeds/{slug}/episodes?season=`,
`GET /api/feeds/{slug}/seasons`, `GET /api/episodes/{id}`, `GET /api/episodes/{id}/adjacent`,
`GET /api/episodes/{slug}/related?limit=` (§6.3), `GET /api/episodes/search?q=` (episodes only),
`GET /api/search?q=` (site-wide: episodes **plus** what plugins contribute about their own content, in
sections per source — see `SearchProvider` below);
`GET/POST/DELETE /api/admin/episodes/{slug}/pins` (curate related, **PODCASTER/ADMIN**);
`GET /api/me`, `DELETE /api/me` (delete the account: core's own data, plus every plugin's
`UserDataHandler`; the answer names any plugin that has not finished, §12),
`GET /api/admin/erasures` + `POST /api/admin/erasures/retry` (**ADMIN**: what a plugin still owes),
`GET/DELETE /api/me/identities`,
`GET/POST/DELETE /api/me/tokens`, `GET/PUT /api/me/progress` (authenticated). All lists paginate; errors are
`application/problem+json`.

**Auth (§8):** Discord `oauth2Login` (active only when `DISCORD_CLIENT_ID`/`SECRET` are set), server-side
cookie sessions (no JWT), CSRF via the `XSRF-TOKEN` cookie (SPA sends it back as `X-XSRF-TOKEN`), and
RBAC (ADMIN/PODCASTER/FAN). Automation uses **personal access tokens** as `Authorization: Bearer …`. The
bootstrap admin is set via `ADMIN_BOOTSTRAP_PROVIDER`/`ADMIN_BOOTSTRAP_EXTERNAL_ID`. Locally, the `dev`
profile's `POST /api/auth/dev-login?role=…` mints a session for any role without Discord.

**Social login needs a secure context.** The session cookie is `Secure` by default, so the OAuth round-trip
only works over **HTTPS** or **`http://localhost`** (browsers special-case localhost). Over plain http on a
LAN IP / hostname (e.g. `http://192.168.x.x:8080`) the browser silently drops the cookie and login fails
with `authorization_request_not_found` — the shell shows a generic "Login failed." Options:

- **Production:** put a TLS-terminating reverse proxy in front (your choice — none is baked into compose)
  and set `MOSAICAST_BASE_URL=https://yourdomain`. The app honors `X-Forwarded-Proto`/`Host`
  (`server.forward-headers-strategy: framework`), so the Discord redirect URI resolves to `https://…`.
- **Plain-http local run:** set `MOSAICAST_SECURITY_SECURE_COOKIE=false` (see `.env.example`). Never do this
  on a real deployment. Also register the matching redirect URI (`<MOSAICAST_BASE_URL>/login/oauth2/code/discord`)
  in the Discord portal.

## Languages

The languages an instance has are decided at **runtime**, not at build time (ARCHITECTURE §12.7). The host
scans two places and `GET /api/i18n/locales` is the answer the shell and every plugin read:

| Source | Where | Notes |
|---|---|---|
| Shipped | `frontend/src/locales/*.json`, copied to the classpath at build time | English and German today |
| Drop-in | `MOSAICAST_LOCALES_DIR/*.json` | added by the operator, read at startup and on each admin page load |

A file is named after its language code — `nl.json`, `pt-br.json`. **Drop-in wins key by key**, so a
partial `en.json` containing one key overrides exactly that string and leaves the rest of English alone;
that is how you rename "Podcast" to "Show" site-wide without forking a catalog you then have to maintain
against every release. A malformed file is skipped with a warning, never fatal.

Nothing is offered until an admin says so. **Admin → Languages** lists everything found, with its origin
and how many strings it is missing against English, and has two independent switches per language:

- **Shell** — the language appears in the switcher. Needs a catalog.
- **Content** — text may be *authored* in it: legal pages, the About blurb, per-locale plugin content.
  Needs no catalog at all, because a Dutch imprint on an English-only site is a real thing to want.

The **default language** lives on that page too (it must be one of the content languages) — it is the last
fallback for anything served per locale. English can never be switched off: it is the source language and
what everything else falls back to.

Adding a language, end to end:

```bash
mkdir -p ./locales
cp frontend/src/locales/en.json ./locales/nl.json   # translate it
# → Admin → Languages → tick Shell and/or Content for Nederlands → Save
```

No restart: the admin page rescans on load, and the shell fetches `/api/i18n/catalog/nl` the first time
anyone selects it.

## Branding assets

The Mosaicast logo and mark ship as the **default branding**, used until an admin uploads custom
branding (ARCHITECTURE §12.1). Accent `#C8553D` matches the default theme seed.

| File | Role |
|---|---|
| `docs/brand/mosaicast-logo.svg` | Design source — full logo (mark + wordmark, light background) |
| `docs/brand/mosaicast-mark.svg` | Design source — square mark (theme-safe on light & dark) |
| `frontend/public/brand/*.svg` | Runtime copies the shell serves: **favicon** and the header **mark** |
| `src/main/resources/branding/*.svg` | Backend default fallback served by `/branding/logo`·`/branding/favicon` (M3) when `SiteConfig` has no custom asset |

The full logo's wordmark uses a dark ink that is only legible on a light background, so the **mark** is
used wherever the theme may be dark (header, favicon). Replace all copies together if the artwork changes.

## Contributing
Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md). In short: `git commit -s` (DCO, required), SPDX header in new files, add tests.

## License
**GNU Affero General Public License v3.0 or later** — see [`LICENSE`](LICENSE). Header per source file:
```
// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors
```

Third-party work redistributed inside the artefacts — currently the Bootstrap Icons artwork inlined
into the shell bundle — is recorded in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

## Name & trademark
"Mosaicast" and the logo denote the official project. Please rename forks.

WhatsApp and Telegram are trademarks of their respective owners; their marks appear in the share
dialog nominatively, to name where a link is being sent. No endorsement or affiliation is implied.
