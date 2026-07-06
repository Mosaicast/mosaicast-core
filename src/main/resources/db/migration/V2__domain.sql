-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- E1 domain: the two-layer model (ARCHITECTURE §4). Identity (episode_ref) is authoritative and owned
-- by us; presentation (episode_display) is a non-authoritative read-through snapshot from the feed,
-- overwritten on every fetch. A feed is a configured source (RSS in v1, capability-driven).

-- A configured feed source. type=manual has no url (host-created planned episodes live here).
CREATE TABLE feed (
    id                    UUID PRIMARY KEY,
    type                  VARCHAR(32)  NOT NULL,
    url                   TEXT,
    title                 TEXT         NOT NULL,
    poll_interval_seconds BIGINT       NOT NULL DEFAULT 1800,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Conditional-GET bookkeeping (ARCHITECTURE §5.4): an unchanged feed costs a 304.
    etag                  TEXT,
    last_modified         TEXT,
    last_fetched_at       TIMESTAMPTZ,
    last_fetch_status     VARCHAR(32),
    last_error            TEXT,
    consecutive_failures  INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Identity layer: the stable internal id every plugin references. Survives feed changes (§4.1).
CREATE TABLE episode_ref (
    id                  UUID PRIMARY KEY,
    feed_id             UUID        NOT NULL REFERENCES feed(id),
    external_guid       TEXT,                              -- null while PLANNED (no feed item yet)
    season              INTEGER,
    episode_no          INTEGER,
    status              VARCHAR(16) NOT NULL,              -- PLANNED | PUBLISHED | WITHDRAWN
    access_type         VARCHAR(16) NOT NULL DEFAULT 'PUBLIC', -- PUBLIC | TIER
    access_tier_ref     TEXT,                              -- set only when access_type = TIER
    provisional_display JSONB,                             -- authoritative display ONLY while PLANNED (§4.3)
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One ref per (feed, guid). NULLs are distinct in Postgres, so many PLANNED refs (null guid) coexist.
    CONSTRAINT uq_episode_ref_feed_guid UNIQUE (feed_id, external_guid)
);

CREATE INDEX idx_episode_ref_feed   ON episode_ref (feed_id);
CREATE INDEX idx_episode_ref_season ON episode_ref (feed_id, season);
CREATE INDEX idx_episode_ref_status ON episode_ref (status);

-- Presentation layer: non-authoritative snapshot from the feed, overwritten on every fetch (§4.2).
CREATE TABLE episode_display (
    episode_ref_id UUID PRIMARY KEY REFERENCES episode_ref(id) ON DELETE CASCADE,
    snapshot       JSONB       NOT NULL,   -- { title, description, audioUrl, publishedAt, duration }
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Full-text search over the display snapshot (episode search, ARCHITECTURE §E1). 'simple' config keeps
-- it language-agnostic since feed content stays in its original language (§12.7).
CREATE INDEX idx_episode_display_fts ON episode_display USING GIN (
    to_tsvector('simple',
        coalesce(snapshot ->> 'title', '') || ' ' || coalesce(snapshot ->> 'description', ''))
);
