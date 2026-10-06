-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- A podcaster can set an episode's season and episode number by hand (ARCHITECTURE §4.4, core#264): a feed
-- cannot carry episode 0 (Apple allows only a non-zero itunes:episode), so a prologue numbered S5E0 arrives
-- as season 5 with no number. `season` / `episode_no` stay the effective numbers every query reads; while
-- `numbers_pinned` is set, polls write only the feed_* record, which is what un-pinning restores.
ALTER TABLE episode_ref ADD COLUMN numbers_pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE episode_ref ADD COLUMN feed_season INTEGER;
ALTER TABLE episode_ref ADD COLUMN feed_episode_no INTEGER;

-- Until now the effective numbers were the feed's, for every row that came from a feed item.
UPDATE episode_ref SET feed_season = season, feed_episode_no = episode_no WHERE external_guid IS NOT NULL;
