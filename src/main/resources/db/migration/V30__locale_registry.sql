-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Which languages an instance offers, and which it lets content be written in (ARCHITECTURE §12.7).
--
-- Until now the answer to "what languages does this site have" lived in `frontend/src/i18n.ts` as two static
-- imports: a build-time constant no operator could change and no plugin could read. Catalogs are now scanned at
-- runtime (bundled + a drop-in directory) and these two columns record what the admin did with what was found.
--
-- Two lists rather than one, because they are genuinely different questions. `ui_locales` is "the shell can
-- render in this" — it needs a catalog. `content_locales` is "text may be authored in this" — legal pages, the
-- About blurb, and per-locale plugin content — and it needs no catalog at all. A Dutch imprint on an
-- English-only site is a real thing to want, and collapsing the two would make it unexpressible.
--
-- The default of ["en","de"] is what the shell already shipped, so an existing install upgrades into exactly the
-- behaviour it had. Both are JSONB for the same reason `ai_crawler_blocked` is: a short list read as a whole,
-- edited as one form, never queried across rows.

ALTER TABLE site_config ADD COLUMN ui_locales      JSONB NOT NULL DEFAULT '["en","de"]'::jsonb;
ALTER TABLE site_config ADD COLUMN content_locales JSONB NOT NULL DEFAULT '["en","de"]'::jsonb;
