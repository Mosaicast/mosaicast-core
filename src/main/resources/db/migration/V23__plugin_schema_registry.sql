-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The platform's bookkeeping for plugin-declared relational schemas (ARCHITECTURE §7.6).
--
-- Flyway is static: it applies a fixed set of scripts shipped with the release, and a plugin's tables are
-- neither fixed nor shipped with it. So per-plugin DDL is applied programmatically by the host's own
-- migration runner, and this table is that runner's record of what it has provisioned. "Never trust plugin
-- DDL" still holds — the plugin declares entities and fields, the host decides what SQL that becomes.
--
-- Why record it at all, when the tables themselves are visible in information_schema: purge has to know
-- which tables belonged to a plugin *after* the plugin folder is gone (§7.8 — removal leaves data intact,
-- and only an explicit purge removes it). Deriving that from a name prefix would mean trusting a prefix
-- match to decide what to drop, which is a destructive operation keyed on a naming convention.

CREATE TABLE plugin_schema_table (
    plugin_id  TEXT        NOT NULL,
    entity     TEXT        NOT NULL,
    table_name TEXT        NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, entity)
);

-- Purge and the boot-time reconcile both ask "what does this plugin own".
CREATE INDEX idx_plugin_schema_table_plugin ON plugin_schema_table (plugin_id);
