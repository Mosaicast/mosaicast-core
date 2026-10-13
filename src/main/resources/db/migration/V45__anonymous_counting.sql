-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Anonymous counting (ARCHITECTURE §19, §18.5): counters per time bucket and subject, never per visitor.
-- Hour buckets are written additively by core and with SET semantics for the edge's reported hours; a
-- nightly job folds hour rows older than 30 days into day rows (span = 'day').
CREATE TABLE metric_counts (
    bucket    TIMESTAMPTZ NOT NULL,
    span      TEXT        NOT NULL CHECK (span IN ('hour', 'day')),
    source    TEXT        NOT NULL CHECK (source IN ('core', 'edge')),
    kind      TEXT        NOT NULL,
    subject   TEXT        NOT NULL,
    milestone SMALLINT    NOT NULL DEFAULT 0,
    count     BIGINT      NOT NULL CHECK (count >= 0),
    PRIMARY KEY (bucket, span, source, kind, subject, milestone)
);
CREATE INDEX metric_counts_span_bucket ON metric_counts (span, bucket);

-- Peaks of a gauge per scope: the edge's waiting count per UTC day ('day:2026-10-18') and per policy
-- revision ('rev:7').
CREATE TABLE metric_gauge (
    name       TEXT        NOT NULL,
    scope      TEXT        NOT NULL,
    max        BIGINT      NOT NULL CHECK (max >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (name, scope)
);

-- The two switches (§19): core's own counting, and the edge's, which travels with the site policy.
ALTER TABLE site_config ADD COLUMN counting BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE site_access ADD COLUMN edge_counting BOOLEAN NOT NULL DEFAULT TRUE;
