# mosaicast-core

> The host: Spring Boot backend + React/Vite shell. Loads plugins, unifies feeds, manages auth/branding/theming.

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

## What is this?
See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Prerequisite: the plugin SDK

Core builds against the published **`@mosaicast/plugin-sdk`** — the TypeScript package on **npm** (public)
and the Java artifacts (`dev.mosaicast:plugin-api` / `plugin-testkit`) in **GitHub Packages**. Pin the
version in `gradle/libs.versions.toml` and `frontend/package.json` (currently `0.1.1`).

- **Frontend:** resolves from public npm — nothing extra, just `npm install`.
- **Backend:** GitHub Packages requires authentication even for reads. Either
  - set `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties` (a PAT with `read:packages`), or export
    `GITHUB_ACTOR` / `GITHUB_TOKEN`; **or**
  - for offline/local work, publish the SDK to your Maven Local from the sibling repo
    (`../mosaicast-plugin-sdk`): `./gradlew publishToMavenLocal` — Gradle checks `mavenLocal()` first, so
    no token is needed.

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
Plugins folder via `MOSAICAST_PLUGINS_DIR` (in the container `/app/plugins`, volume `./plugins`).
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

## Project layout

```
src/main/java/dev/mosaicast/core/
  episode/   EpisodeRef identity + display snapshot, read/search API (ARCHITECTURE §4, §6)
  feed/      FeedSource SPI, RssFeedSource, reconciler, ShedLock scheduler, admin API (§5)
  auth/      User + LinkedIdentity, Discord oauth2Login, merging rules, /api/me, PATs (§8)
  config/    security (oauth2/session/CSRF/RBAC), headers, scheduling/ShedLock
  web/       SPA serving, RFC 7807 handling, pagination envelope
src/main/resources/
  db/migration/   Flyway migrations (schema is Flyway-only)
  branding/       default logo/mark fallback (§12)
  static/         built shell bundle (generated by the frontend build; gitignored)
frontend/    React/Vite host shell (built into resources/static)
```

Key API: `POST /api/admin/feeds` (add + preview + refresh, **PODCASTER/ADMIN**),
`GET /api/feeds/{id}/episodes?season=`, `GET /api/feeds/{id}/seasons`, `GET /api/episodes/{id}`,
`GET /api/episodes/search?q=` (public read); `GET /api/me`, `GET/DELETE /api/me/identities`,
`GET/POST/DELETE /api/me/tokens` (authenticated). All lists paginate; errors are
`application/problem+json`.

**Auth (§8):** Discord `oauth2Login` (active only when `DISCORD_CLIENT_ID`/`SECRET` are set), server-side
cookie sessions (no JWT), CSRF via the `XSRF-TOKEN` cookie (SPA sends it back as `X-XSRF-TOKEN`), and
RBAC (ADMIN/PODCASTER/FAN). Automation uses **personal access tokens** as `Authorization: Bearer …`. The
bootstrap admin is set via `ADMIN_BOOTSTRAP_PROVIDER`/`ADMIN_BOOTSTRAP_EXTERNAL_ID`. Locally, the `dev`
profile's `POST /api/auth/dev-login?role=…` mints a session for any role without Discord.

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

## Name & trademark
"Mosaicast" and the logo denote the official project. Please rename forks.
