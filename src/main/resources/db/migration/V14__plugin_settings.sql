-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- E5b plugin system: host-owned settings per plugin (ARCHITECTURE §7.2, §7.8, §8.5).
--
-- Two separate concerns, deliberately not merged into one row:
--   * plugin_activation — whether an admin has switched the plugin off. An ABSENT row means enabled, so
--     installing a plugin needs no bookkeeping and this table only ever holds explicit admin decisions.
--   * plugin_config     — admin-set overrides for the fields a manifest declares. Absent key = the
--     manifest default still wins, so removing an override is a DELETE, not a magic value.
--
-- Neither is plugin *data*: "purge plugin data" (§7.8) clears plugin_data only and leaves both of these
-- intact, so purging a plugin never silently re-enables it or resets its configuration.

CREATE TABLE plugin_activation (
    plugin_id  TEXT        PRIMARY KEY,       -- manifest id; no FK, plugins live on disk
    enabled    BOOLEAN     NOT NULL,          -- explicit admin decision; absent row = enabled
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plugin_config (
    plugin_id  TEXT        NOT NULL,          -- manifest id (hard scope)
    key        TEXT        NOT NULL,          -- a field name declared in the manifest's "config" block
    value      JSONB       NOT NULL,          -- the override; type-checked against the declared field type
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, key)
);
