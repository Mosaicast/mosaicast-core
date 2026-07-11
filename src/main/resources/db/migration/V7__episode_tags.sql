-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Episode tags (ARCHITECTURE §6.1/§6.3): feed-derived keywords/categories (itunes:keywords, <category>),
-- a filter axis for the shell and the basis for later host-defined subfeeds. Overwritten per poll from the
-- feed (presentation-derived, non-authoritative). A normalized table so we can both filter ("episodes with
-- tag T") and list distinct tags (optionally per feed).

CREATE TABLE episode_tag (
    episode_ref_id UUID NOT NULL REFERENCES episode_ref(id) ON DELETE CASCADE,
    tag            TEXT NOT NULL,
    PRIMARY KEY (episode_ref_id, tag)
);

CREATE INDEX idx_episode_tag_tag ON episode_tag (tag);
