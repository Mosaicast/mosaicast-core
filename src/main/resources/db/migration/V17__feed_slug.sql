-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Feed public slug (ARCHITECTURE §4.1, §6.1) — the counterpart of the episode slug (V13). Episodes have
-- lived at /episodes/the-sample-cast-s01e06 since 0.5.2 while their own show lived at
-- /feeds/7a48fcb5-0fe5-429e-a1b6-187002b35940; the feed is now addressed the same way.
--
-- Added nullable + unique; existing rows are backfilled at boot (FeedSlugBackfill), since a per-row slug
-- cannot be computed by a constant default. The UUID remains the internal key.

ALTER TABLE feed ADD COLUMN slug TEXT;

CREATE UNIQUE INDEX ux_feed_slug ON feed (slug);
