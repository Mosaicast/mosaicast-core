-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Episode public slug (ARCHITECTURE §4.1): a stable, human-readable identifier used in URLs, the episode API
-- and the plugin contract, while the UUID stays the internal key. Minted once at creation and immutable.
-- The column is added nullable + unique; existing rows are backfilled at boot (EpisodeSlugBackfill), since a
-- per-row slug can't be computed by a constant default.

ALTER TABLE episode_ref ADD COLUMN slug TEXT;

CREATE UNIQUE INDEX ux_episode_ref_slug ON episode_ref (slug);
