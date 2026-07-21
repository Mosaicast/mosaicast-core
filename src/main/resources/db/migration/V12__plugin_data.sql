-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- E5a plugin system (ARCHITECTURE §7.6). The generic per-plugin document store: every plugin gets a
-- hard-scoped JSONB key/value space keyed by (plugin_id, scope). This is the default persistence path for
-- plugins (schema-backed storage is deferred, §7.6). Rows are last-write-wins; the host mediates all access
-- so a plugin can never read another plugin's data.

CREATE TABLE plugin_data (
    plugin_id  TEXT        NOT NULL,          -- owning plugin id from its manifest (hard scope)
    scope_type TEXT        NOT NULL,          -- site | feed | season | episode (Scope.type, lower-case)
    scope_id   TEXT        NOT NULL,          -- Scope.id ("main" for the site singleton)
    key        TEXT        NOT NULL,          -- doc key, matches DocStore.KEY_PATTERN
    value      JSONB       NOT NULL,          -- arbitrary JSON document
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, scope_type, scope_id, key)
);

-- Containment / existence queries over the JSON document body (§7.6).
CREATE INDEX idx_plugin_data_value ON plugin_data USING GIN (value);
