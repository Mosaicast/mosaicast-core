-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Legal page slugs now have a grammar (lowercase letters and digits in hyphen-separated runs, at most 64
-- characters), enforced when a page is created. Before, anything non-blank was accepted, and a slug such as
-- `qa test/2` produced a page no link reached, whose saves went nowhere and whose delete could not route:
-- removable only here, in the database (core#164).
--
-- This repairs the rows created before the rule. Each is rewritten to the grammar and suffixed with the
-- start of its own id, so two broken slugs that normalise to the same text cannot collide with each other or
-- with a valid page. Translations hang on the page id, not the slug, so nothing else moves. The old address
-- never worked, so no working link breaks.
UPDATE legal_page
SET slug = left(
        coalesce(nullif(trim(BOTH '-' FROM regexp_replace(lower(slug), '[^a-z0-9]+', '-', 'g')), ''), 'page'),
        55) || '-' || left(replace(id::text, '-', ''), 8)
WHERE slug !~ '^[a-z0-9]+(-[a-z0-9]+)*$' OR length(slug) > 64;

-- `left(…, 55)` can end on a hyphen, and the suffix then doubles it.
UPDATE legal_page
SET slug = regexp_replace(slug, '-{2,}', '-', 'g')
WHERE slug ~ '-{2,}';
