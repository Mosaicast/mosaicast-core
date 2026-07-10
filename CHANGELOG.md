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

[Unreleased]: https://github.com/Mosaicast/mosaicast-core/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Mosaicast/mosaicast-core/releases/tag/v0.1.0
