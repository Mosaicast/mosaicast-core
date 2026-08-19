-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Seed the "this instance" blurb shown on /about, between the fixed text about Mosaicast and the list of
-- what this install runs.
--
-- It lives in `legal_page` to inherit the mini-CMS (ARCHITECTURE §12.6): per-locale markdown, server-side
-- sanitising, locale fallback and the tabbed admin editor, none of which a `site_config` column would give
-- us — and per-locale text there would mean reinventing `legal_page_translation`.
--
-- The `about` role marker keeps it OUT of the footer's legal group (LegalService.footer filters on it):
-- it is authored like a legal page but is not one, and listing it beside privacy and imprint would say
-- something untrue about what it is.
--
-- Seeded rather than left empty so a fresh install has something to edit instead of an empty page and no
-- hint that it can be filled. Idempotent: ON CONFLICT DO NOTHING never clobbers an admin's own edits.
WITH p AS (
    INSERT INTO legal_page (id, slug, role_marker, sort_order)
    VALUES (gen_random_uuid(), 'about', 'about', 100)
    ON CONFLICT (slug) DO NOTHING
    RETURNING id
)
INSERT INTO legal_page_translation (id, page_id, locale, title, markdown)
SELECT gen_random_uuid(), p.id, v.locale, v.title, v.markdown
FROM p, (VALUES
    ('en', 'About this instance', $md$Tell your listeners who runs this site.

This text is yours — edit or delete it under **Admin → Legal pages → about**. A sentence or two about the
show, the people behind it, or why this site exists is usually enough. Everything below is filled in
automatically from what this install is actually running.
$md$),
    ('de', 'Über diese Instanz', $md$Erzähl deinen Hörerinnen und Hörern, wer diese Seite betreibt.

Dieser Text gehört dir — bearbeite oder lösche ihn unter **Admin → Rechtliche Seiten → about**. Ein bis
zwei Sätze über den Podcast, die Menschen dahinter oder den Zweck dieser Seite genügen meistens. Alles
Weitere unten wird automatisch aus dem erzeugt, was diese Installation tatsächlich ausführt.
$md$)
) AS v(locale, title, markdown);
