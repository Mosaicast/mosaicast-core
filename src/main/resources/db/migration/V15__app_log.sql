-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The host's own operational log (ARCHITECTURE §13 observability). Until now everything the system knew about
-- its failures — a rejected plugin, a feed that stopped polling, an unhandled 500 — went to stdout and died
-- with the container. An operator without a terminal had no way to find out why a plugin vanished from the
-- site. This table backs the admin log/health viewer.
--
-- Bounded on purpose: rows are pruned by age and by a row cap (see AppLogProperties), so the table cannot grow
-- without limit on a long-running instance. It is diagnostics, not an audit trail of record.

CREATE TABLE app_log (
    id         BIGSERIAL   PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    level      TEXT        NOT NULL,          -- ERROR | WARN | INFO | DEBUG
    subsystem  TEXT        NOT NULL,          -- plugins | feeds | auth | web | consent | plugin | …
    source     TEXT,                          -- logger class, or 'frontend' for plugin-reported entries
    plugin_id  TEXT,                          -- set whenever the entry is attributable to one plugin
    message    TEXT        NOT NULL,
    detail     TEXT,                          -- stack trace / long form
    context    JSONB                          -- structured extras (feedId, subpath, …)
);

-- The viewer always sorts newest-first and filters on one of these three axes.
CREATE INDEX idx_app_log_at           ON app_log (at DESC);
CREATE INDEX idx_app_log_level_at     ON app_log (level, at DESC);
CREATE INDEX idx_app_log_subsystem_at ON app_log (subsystem, at DESC);
CREATE INDEX idx_app_log_plugin_at    ON app_log (plugin_id, at DESC) WHERE plugin_id IS NOT NULL;
