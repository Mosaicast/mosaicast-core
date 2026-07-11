-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Channel-level presentation for the feed panel (ARCHITECTURE §6.1): the show cover, author and description
-- from the RSS channel, refreshed on each changed poll. Non-authoritative (feed-derived), like the episode
-- display snapshot.

ALTER TABLE feed ADD COLUMN image_url   TEXT;
ALTER TABLE feed ADD COLUMN author      TEXT;
ALTER TABLE feed ADD COLUMN description TEXT;
