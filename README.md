# mosaicast-core

> The host: Spring Boot backend + React/Vite shell. Loads plugins, unifies feeds, manages auth/branding/theming.

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

## What is this?
See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Prerequisite: the plugin SDK (pre-publish)

Core builds against **`@mosaicast/plugin-sdk`** (TS) and **`dev.mosaicast:plugin-api`** (Java). The SDK
lives in a sibling repo and is **not on a public registry yet**, so link it locally first (this is a
temporary, uncommitted step — the committed dependency specs stay at the plain version `0.1.0`):

```bash
# In the SDK repo (../mosaicast-plugin-sdk):
./gradlew publishToMavenLocal     # → dev.mosaicast:plugin-api / plugin-testkit in ~/.m2
npm ci && npm run build && npm link   # global link of @mosaicast/plugin-sdk (target = SDK repo root)
```

Gradle resolves the SDK from `mavenLocal()`; the frontend resolves it via `npm link` (below). Once the
SDK is published, these steps disappear and the versions resolve from Maven Central / npm directly.

## Build & test

```bash
./gradlew build            # backend: compile + unit + Testcontainers integration tests (needs Docker)

cd frontend
npm install
npm link @mosaicast/plugin-sdk   # link the SDK (see above); do this after install
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

The **Dockerfile** is multi-stage (Vite build → Gradle `bootJar` → slim JRE, shell baked in). Pre-publish,
`docker compose build` needs the SDK provided as BuildKit build contexts — see the header of `Dockerfile`.
Plugins folder via `MOSAICAST_PLUGINS_DIR` (in the container `/app/plugins`, volume `./plugins`).
Layout reference for the shell: `docs/reference/mosaicast-mockup.jsx` (NOT the real architecture).

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
