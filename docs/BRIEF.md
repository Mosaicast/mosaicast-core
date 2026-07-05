# Brief: mosaicast-core

> Prerequisite: read `docs/ARCHITECTURE.md` fully. This is the largest repo — build it in stages.
> Depends on `mosaicast-plugin-sdk` (Maven Local / npm link).

## Purpose
The **host**: Spring Boot backend (REST) + React/Vite shell. Loads plugins at startup, unifies feeds, manages auth/branding/theming, provides `ctx`/`PluginContext` to all plugins.

## Stages (build in this order)

### E1 – Domain & feed pipeline (ARCHITECTURE §4, §5)
- `EpisodeRef` (identity, status PLANNED/PUBLISHED/WITHDRAWN, season relation, access), Flyway schema.
- Display snapshot as a read-through cache (`episode_display`), **never authoritative**.
- `FeedSource` SPI + `RssFeedSource` (Rome). Capability-driven.
- **Reconciler** (3 cases + PLANNED binding via season/episode no., otherwise fuzzy-title suggestion). No hard delete → WITHDRAWN.
- **Scheduler** per feed config, **ShedLock**-wrapped, polling with **conditional GET** (ETag/If-Modified-Since). "Refresh now" endpoint.
- Create planned episodes (admin/podcaster): a manual `EpisodeRef` with `provisional_display`.
- **Episode search**: simple full-text over the display snapshots (Postgres FTS or trigram), paginated endpoint.
- **List endpoints paginate from day one**; errors as RFC 7807 problem+json (ARCHITECTURE §13).

### E2 – Auth & identity (ARCHITECTURE §8)
- Spring Security `oauth2Login`, **Discord** first (prepare Patreon/Google as later providers, don't build them).
- `User` + `LinkedIdentity`. **Merging rules exactly** as §8.3 (verified email OR logged in, otherwise explicit).
- `GET /api/me/identities` (provider list with `linked` flag); link flow (merging case 2); **last identity not removable**.
- Sessions: httpOnly cookie + Spring Session (in-memory in v1, Redis-ready). **No JWT.**
- RBAC ADMIN/PODCASTER/FAN, `role` on the user; **bootstrap admin via env**.
- **Personal access tokens** (podcaster-scoped) for automation (stats upload).

### E3 – Storage, branding & theming (ARCHITECTURE §11, §12)
- `BlobStore` interface + `PostgresBlobStore` (BYTEA), **streaming + range**, `urlFor`, namespace routing. (S3/FS later.)
- `SiteConfig` in DB (name, logo, favicon, optional dark logo, theme_seed). `GET /api/site`.
- Asset endpoints `/branding/logo`, `/branding/favicon` with **ETag**. **SVG security** (sanitize or raster-only).
- Admin settings API for branding + seed. **SVG upload validation.**
- **Legal pages mini-CMS** (ARCHITECTURE §12.6): admin CRUD for static markdown pages (slug, title, per-locale bodies, sortable order, role marker `privacy`/`imprint`/`terms`), automatic footer links, sanitized rendering. Consent links to the `privacy`-marked page.

### E4 – React/Vite shell (ARCHITECTURE §6, §12.3; `docs/reference/mosaicast-mockup.jsx` as a LAYOUT reference)
- Top-bar chrome (nav collapses on mobile), **persistent global player** across routes, **Media Session API**, **auto-advance to the next episode**.
- Feed view: wide cards (2 columns on desktop), host **filter bar** (season dropdown, sorting), `card` slots, lock/upcoming stubs.
- Detail page: two columns (`main`/`sidebar` regions), hero with play, **fixed previous/next navigation** (separate from related).
- Semantic **theme tokens**, light/dark, `data-theme`, **no-flash inline script**, accent **seed generator** (OKLCH + WCAG clamp), live preview, logo light/dark preview.
- **i18n** (ARCHITECTURE §12.7): i18next with `locales/en.json` (source) + `locales/de.json`; locale resolution = explicit choice (works anonymously, persisted) → browser → site default; visible **language switcher** without login; dates via `Intl`. Feed content stays in its original language.
- **Filter state in the URL** (query params, §6.1) — filtered views shareable/bookmarkable, back button works.
- **Server-side share metadata** (§6.4): serve `index.html` with injected OG/Twitter tags per URL — **OgResolver** for episode/feed/season/site (reads query params); for `/p/{pluginId}/*` ask the plugin's `ShareMetadataProvider`, fallback site OG.
- **Accessibility (WCAG AA):** keyboard-operable player (space/arrows, skip), focus states, aria labels; contrast is already clamped by the seed generator.
- **Player extras:** playback **speed control** (1×/1.25×/1.5×/2×) and skip ±15/30 s — table stakes for podcast players.
- **Share button** on episode pages (native share API, clipboard fallback) — the OG tags (§6.4) make the shared link look right.
- **Empty states / first run:** fresh install greets the admin with "add your first feed" instead of a blank page; feed-add form validates the URL and shows a preview before saving.
- Feed view: **infinite scroll / pagination** against the paginated API; a search box using the episode-search endpoint.
- Admin area (its own left nav allowed): branding, feeds, planned episodes, user roles, plugin activation, legal pages, robots/AI-crawler policy.

### E5 – Plugin system (ARCHITECTURE §7)
- **PF4J** loading from the plugins folder at startup; `platformApi` check → reject incompatible. **Budget real time for classloader setup** (PF4J inside a Spring Boot fat JAR is a known rough edge; plugins staying Spring-free is the mitigation — ARCHITECTURE §7.1 note).
- **Plugin deep links**: reserve `/p/{pluginId}/*`, pass the subpath as `ctx.route` (+ `onChange`); dispatch share-metadata requests to `ShareMetadataProvider` (§6.4) and collect sitemap URLs via `SitemapProvider` (§6.6).
- **Failure isolation (§7.8):** a plugin that fails to load is **disabled with an admin warning**, core keeps booting; the shell wraps **every slot in an error boundary**. Plugin removal = dormant (data retained); explicit admin **"purge plugin data"** action for both stores.
- **Generic plugin config form:** render the manifest `config` schema as an admin form (respecting `editableBy`) — plugins bring no config UI.
- `PluginContext` (backend) incl. `DocStore` (`plugin_data` JSONB + GIN, hard-scoped), `FeedAccess.episodesIn(scope)`, `onSchedule` (ShedLock), `PluginConfig`.
- **Schema provider** (declared entities → namespaced tables via Flyway, cleanup on uninstall).
- Assets under `/plugins/{id}/assets/*`; backend endpoints `/api/plugins/{id}/*`.
- `GET /api/plugins/manifest` (active plugins + frontend entry + slots + visibleTo).
- Shell: inject plugin JS modules **once** (register custom elements), mount the matching slots into the regions per view, set `ctx` (scope, **episodes[] resolved by the host**, user, api, consent, **filter** read-only, **player**, theme). Stacking by `order`.

### Cross-cutting
- **RelatedProvider** (core, swappable strategy): v1 = pinned → season/tags/fuzzy, on-request + cached. **Sequential nav** (prev/next) separate and always shown.
- **Consent service** (platform, §12.5): aggregate categories from plugin manifests, provide `ctx.consent`.
- **Access/gating** (§10): host decides `unlocked`; v1 everything PUBLIC.
- **Listening progress service** (§6.5, core): player stores per-user position (anonymous localStorage / logged-in server-side), resumes playback, exposes `ctx.progress` to plugins (bingo spoiler protection consumes it).
- **SEO & crawlers** (§6.6): dynamic `sitemap.xml` (incl. plugin `SitemapProvider`), `robots.txt` with **admin-configurable AI-crawler policy** (+ optional llms.txt), JSON-LD (`PodcastSeries`/`PodcastEpisode`), canonical + hreflang + RSS discovery link, a server-rendered no-JS content block in `index.html`, real 404 status codes.
- **Security & ops baseline (§13):** CSRF (`XSRF-TOKEN` cookie) + `SameSite=Lax`, baseline security headers (CSP), upload size limits, basic rate limiting on auth/upload, Spring **Actuator** health/info wired into the compose healthcheck.

## Definition of Done (v1)
Fresh DB → compose up → admin can log in via Discord (bootstrap), add an RSS feed, episodes appear unified with a season filter (filter state in the URL), branding/theme settable in admin (light/dark + seed), create a planned episode, legal pages appear as footer links, the UI switches language (de/en) without login, a shared episode link shows a correct preview (OG tags), playback resumes where the user left off, and a built sample plugin from the plugins folder is loaded at startup and renders in its slots. Additionally: `sitemap.xml` and `robots.txt` are served, an episode page carries `PodcastEpisode` JSON-LD and readable no-JS content, `/actuator/health` is green (compose healthcheck), episode search returns paginated results, and a deliberately broken plugin is disabled at startup with an admin warning instead of crashing core.

**Tests (ARCHITECTURE §13.5):** unit tests at the risk points (reconciler incl. PLANNED binding, account merging rules §8.3, fuzzy match, theme seed contrast clamp, BlobStore range); integration tests with **Testcontainers** (Postgres + Flyway + PF4J plugin loading); shell component tests (Vitest) for slot mounting.

## Local stack
`docker-compose.yml` + `.env.example` are in the repo root. Create the **Dockerfile** (multi-stage: Gradle build → slim JRE image, frontend built in). `MOSAICAST_PLUGINS_DIR` is `/app/plugins` in the container (volume `./plugins`).

## SDK & license
- Consume the SDK via `mavenLocal()`/composite build (`includeBuild`) and `npm link` (ARCHITECTURE §3.5). **Exact signatures from the SDK Javadoc/TSDoc, don't guess.**
- **License: AGPLv3** (protective core). SPDX headers in source files. Take `CONTRIBUTING.md` + the DCO workflow from the templates.
