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
