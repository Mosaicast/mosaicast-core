# Project: Mosaicast – mosaicast-core

The host: Spring Boot backend + React/Vite shell. Loads plugins, unifies feeds, manages auth/branding/theming.

## Read first (mandatory)
- `docs/ARCHITECTURE.md` — source of truth for the whole system. On conflict, this file wins.
- `docs/BRIEF.md` — what THIS repo builds, scope, public contract, tasks.

Read both fully before writing code. Work in plan mode first.

## Tech stack
Java 21 · Spring Boot 3 · PostgreSQL · PF4J · React + Vite

## Commands
```
./gradlew build
./gradlew test
```

## Conventions (binding)
- Java packages `dev.mosaicast.*`; npm scope `@mosaicast`.
- Plugins import ONLY against the SDK, never against core code.
- The manifest `platformApi` must match the built SDK version.
- Never commit secrets; configure via `.env` / environment variables.
- Migrations exclusively via Flyway.
- **Tests are part of the work** (see DoD in the BRIEF, ARCHITECTURE §13.5; plugins test against the SDK test kit).
- **CI:** create and maintain `.github/workflows/ci.yml` (build + tests on every PR) as soon as the build exists; the Definition of Done includes green CI.
- **Document public APIs** (Javadoc/TSDoc); take SDK signatures from the built SDK docs, don't guess (§3.5).
- **Sign off commits** (`git commit -s`, DCO).
- **SPDX header in EVERY new source file**:
  `// SPDX-License-Identifier: AGPL-3.0-or-later`
  `// SPDX-FileCopyrightText: 2026 The Mosaicast Authors`
  Don't guess the copyright holder from git config — use this fixed value. CI blocks PRs without a header.

## Frontend & UI (binding)
- **Plain CSS, flat `mc-*` class names. No Tailwind, no shadcn, no CSS-in-JS, no component library** — do
  not introduce one, and ignore skill guidance that assumes them. `frontend/src/styles.css` is an `@import`
  manifest; the rules live in `frontend/src/styles/*.css` (`tokens base chrome feed detail player forms
  admin consent`). Import order is the cascade — put a rule in the partial that owns the surface.
- **`--mc-*` custom properties are a contract with plugins** (ARCHITECTURE §12.3): they inherit across the
  shadow boundary, so plugin Web Components read the same tokens, which is what makes plugin UIs re-theme
  automatically. Renaming or dropping one breaks every plugin. Add tokens freely; change existing names only
  deliberately. Style with tokens, not literals — `styles/tokens.css` documents the full set, and the eight
  colour tokens are the only ones also delivered as JS (`ctx.theme`), mirrored in `theme/applyTheme.ts` and
  `public/theme-init.js`.
- **Light and dark are both first-class** (`data-theme` on the root; `frontend/public/theme-init.js` sets it
  before first paint to avoid a flash). Check both for every visual change.
- **i18n:** flat dotted keys in `frontend/src/locales/{en,de}.json`. **Both locales, always** — German is a
  launch-market requirement, not a translation afterthought. No hardcoded user-facing strings.
- Plugin UIs mount inside `SlotRegion`; shell CSS styles the region, never a plugin's internals.
- **Structure is spec, looks are not.** ARCHITECTURE binds *what must exist and why* — per-feed tabs and the
  feed/site scope panels (§6.1), the slot region names plugins declare against (`top card main sidebar player
  feed site admin page`, §7.3), roles and admin surfaces (§8.5) — not how any of it looks. Visual design,
  layout within those structures, spacing, type and colour are free to change; a region must keep existing
  and stay somewhere sensible, because a plugin targeting it would otherwise render nowhere.
- Commands: `cd frontend && npm test && npx tsc --noEmit && npm run build`. **`npm run build` writes into
  `src/main/resources/static`, but a running app serves `build/resources/main` — restart the app after
  building or you are looking at the previous bundle.**

## Architecture guardrails (do not violate)
- Identity (`EpisodeRef`) is separate from presentation (feed snapshot). Runtime/date in the core display come from the feed; plugin metrics are non-authoritative and live only in the plugin UI.
- The host resolves scopes and decides access/filters — plugins only consume.
- The generic doc store is the default; schema tables only platform-mediated (declarative).

## Keep docs current (continuously)
- Keep **README.md** and **this CLAUDE.md** up to date (commands, structure, setup, conventions) — repo-local, your job.
- **ARCHITECTURE.md and BRIEF.md are READ-ONLY specs** — don't change them unilaterally; flag deviations.
- Keep CLAUDE.md slim (< ~200 lines); leave incidental learnings to Claude Code's auto memory.
- **Screenshots:** after a change that alters the shell's look, refresh the README screenshots in
  `assets/screenshots/` — **home + detail, each light and dark** (`home-{light,dark}.png`,
  `detail-{light,dark}.png`, ~1280px). Use `dev/screenshots.sh up` (a fleeting Postgres on :5433 + the
  fictional `assets/sample/sample-feed.xml` — never your real dev DB, never a real podcast), capture with
  the browser tools, then `dev/screenshots.sh down`. Brand assets (logo light/dark) live in `assets/`.

## When unsure
Ask, or note the assumption visibly, instead of silently diverging from ARCHITECTURE.md.
