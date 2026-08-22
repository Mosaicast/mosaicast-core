-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Tags become a site-wide vocabulary plugins can read and contribute to (ARCHITECTURE §6.1, SDK 0.9.0).
--
-- Three things change, and the first is the one everything else waits on:
--
--   1. `episode_tag` gains a `source`. The reconciler used to delete every tag on an episode before
--      re-inserting the feed's, so any tag a plugin or a podcaster added died at the next poll. With a
--      provenance column the wipe narrows to the rows the feed owns.
--   2. A `tag` table holds the vocabulary: the canonical key every writer converges on, plus the display
--      label kept from first use. Feed values were stored verbatim, so `Maritime`, `maritime` and
--      `maritime ` were three tags — survivable as one feed's keywords, not survivable as a vocabulary
--      several writers share.
--   3. `plugin_tag` holds a plugin's assignments against its own opaque subject keys, the same namespacing
--      property the schema store has for tables and `ctx.route.navigate` has for URLs.
--
-- The canonical rule is inlined here (trim, collapse internal whitespace, casefold) rather than installed
-- as a function: from here on it lives in Java (`TagKeys.canonical`), and a second copy in the database
-- would be free to disagree with it. This migration is the one place both had to agree.

-- ---------------------------------------------------------------------------------------------------
-- The vocabulary, seeded from what the feeds already produced.
-- ---------------------------------------------------------------------------------------------------

CREATE TABLE tag (
    tag        TEXT PRIMARY KEY,
    label      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The label is the most-used raw spelling, ties broken alphabetically: existing tags keep the casing a
-- visitor already saw, rather than being lower-cased on screen by a migration they did not ask for.
INSERT INTO tag (tag, label)
SELECT canon, label
FROM (
    SELECT canon, label,
           row_number() OVER (PARTITION BY canon ORDER BY uses DESC, label ASC) AS rn
    FROM (
        SELECT lower(btrim(regexp_replace(tag, '\s+', ' ', 'g'))) AS canon,
               btrim(regexp_replace(tag, '\s+', ' ', 'g'))        AS label,
               count(*)                                           AS uses
        FROM episode_tag
        GROUP BY 1, 2
    ) spellings
) ranked
WHERE rn = 1 AND canon <> '';

-- ---------------------------------------------------------------------------------------------------
-- Provenance and canonical keys on the existing assignments.
-- ---------------------------------------------------------------------------------------------------

ALTER TABLE episode_tag ADD COLUMN source TEXT NOT NULL DEFAULT 'feed';

-- Near-duplicate spellings on one episode collapse into a single row before the key narrows to include
-- the canonical form; the survivor is the alphabetically first spelling, which is arbitrary but stable.
DELETE FROM episode_tag a
USING episode_tag b
WHERE a.episode_ref_id = b.episode_ref_id
  AND lower(btrim(regexp_replace(a.tag, '\s+', ' ', 'g')))
    = lower(btrim(regexp_replace(b.tag, '\s+', ' ', 'g')))
  AND a.tag > b.tag;

-- A tag that is whitespace only carries no meaning and cannot be a vocabulary entry.
DELETE FROM episode_tag WHERE btrim(tag) = '';

UPDATE episode_tag SET tag = lower(btrim(regexp_replace(tag, '\s+', ' ', 'g')));

ALTER TABLE episode_tag DROP CONSTRAINT episode_tag_pkey;
ALTER TABLE episode_tag ADD PRIMARY KEY (episode_ref_id, tag, source);

-- The vocabulary is now the referent: an assignment can only name a tag that exists, and a tag curated
-- away in admin takes its assignments with it.
ALTER TABLE episode_tag
    ADD CONSTRAINT fk_episode_tag_tag FOREIGN KEY (tag) REFERENCES tag (tag) ON DELETE CASCADE;

CREATE INDEX idx_episode_tag_source ON episode_tag (episode_ref_id, source);

-- ---------------------------------------------------------------------------------------------------
-- A plugin's assignments against its own subjects.
-- ---------------------------------------------------------------------------------------------------

CREATE TABLE plugin_tag (
    plugin_id   TEXT NOT NULL,
    subject_key TEXT NOT NULL,
    tag         TEXT NOT NULL REFERENCES tag (tag) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, subject_key, tag)
);

CREATE INDEX idx_plugin_tag_tag ON plugin_tag (tag);
