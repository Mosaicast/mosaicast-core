-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Fuzzy PLANNED-binding suggestions (ARCHITECTURE §5.3). When a feed item fuzzy-matches a planned episode
-- (no exact season/episode match), the reconciler records a suggestion here for the podcaster to confirm —
-- never auto-applied. Confirming binds the planned episode to the feed item (PLANNED -> PUBLISHED).

CREATE TABLE binding_suggestion (
    id             UUID PRIMARY KEY,
    feed_id        UUID        NOT NULL REFERENCES feed(id) ON DELETE CASCADE,
    planned_ref_id UUID        NOT NULL REFERENCES episode_ref(id) ON DELETE CASCADE,
    raw_guid       TEXT        NOT NULL,       -- the feed item's GUID (its auto-created ref holds it)
    planned_title  TEXT        NOT NULL,
    raw_title      TEXT        NOT NULL,
    similarity     DOUBLE PRECISION NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_binding_suggestion UNIQUE (feed_id, planned_ref_id, raw_guid)
);

CREATE INDEX idx_binding_suggestion_feed ON binding_suggestion (feed_id);
