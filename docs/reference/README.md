# reference/ — layout reference

## `mosaicast-mockup.jsx`

**What it is:** a clickable single-file mockup showing the **look and layout** of the `mosaicast-core` shell — top bar, persistent player, feed of wide cards, detail page with `main`/`sidebar`, the `card` slot, light/dark via semantic tokens, the accent **seed**, the **slot overlay** (showing the plugin regions `top`/`card`/`main`/`sidebar`/`player`), per-host bingo tabs and the upcoming/PLANNED state.

**What it is NOT:** the real architecture. It is **fake data in a single file**, with no real API, no Web Component plugin system, no real theming backend.

## Instruction to the core instance
Reproduce **look, layout and slot regions** from this mockup, but build the **real** structure per `docs/ARCHITECTURE.md` (React/Vite shell, Web Component plugins via `ctx`, semantic tokens from `SiteConfig`, real REST API). Use the mockup as a visual template, **do not copy the code** as architecture.

## Deliberate deltas (not in the mockup yet, required by the core brief)
- **Previous/next episode** as fixed navigation (detail page + player), separate from "related episodes".
- **Player auto-advance** to the next episode at the end.
- Real semantic token values come from the **seed generator** (OKLCH + WCAG clamp), not hardcoded.
