# mosaicast-core

> The host: Spring Boot backend + React/Vite shell. Loads plugins, unifies feeds, manages auth/branding/theming.

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

## What is this?
See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Build & test
```bash
./gradlew build
./gradlew test
```

## Run locally
```bash
cp .env.example .env      # fill in (DB password, Discord OAuth, bootstrap admin)
docker compose up --build # -> http://localhost:8080
```
The **Dockerfile** is created by the agent (multi-stage: Gradle build -> JRE, frontend built in).
Plugins folder via `MOSAICAST_PLUGINS_DIR` (in the container `/app/plugins`, volume `./plugins`).
Layout reference for the shell: `docs/reference/mosaicast-mockup.jsx` (NOT the real architecture).

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
