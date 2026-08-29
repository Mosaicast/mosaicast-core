-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Which third-party service an instance uses for each kind, and how it is configured (ARCHITECTURE §12.7).
--
-- Two tables rather than one: the selection is per kind and there is at most one, while settings are per
-- provider and there are many. Folding them together would mean either a row whose columns are mostly null or
-- a settings blob keyed on a choice that changes.
--
-- No foreign key to anything naming a provider. Providers are Spring beans on the classpath, exactly as
-- `plugin_activation` records ids of plugins that live on disk: the database cannot check a reference to
-- something that is not in it, and pretending otherwise would only break the day a provider is renamed.
--
-- Settings survive a provider switch on purpose, keyed by (kind, provider_id) rather than by the current
-- selection. An admin who tries Google for an afternoon and switches back should find the LibreTranslate URL
-- they typed still there — the same instinct that keeps `plugin_config` through a data purge.
--
-- ABSENT MEANS OFF, which is the opposite of `plugin_activation`, where absent means enabled. A plugin nobody
-- toggled should work, so a fresh install is useful; a third-party service nobody configured must make no
-- outbound call at all. Auto-selecting one would have a fresh install talking to somebody else's server on
-- the strength of a default nobody chose.

CREATE TABLE external_service_selection (
    kind        TEXT        PRIMARY KEY,   -- ExternalServiceKind.id(), e.g. 'translation'
    provider_id TEXT,                      -- NULL = explicitly none; absent row = never chosen
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One admin-set value for one field a provider declares. Absent means the descriptor default still applies,
-- so clearing an override deletes the row rather than storing a sentinel (as `plugin_config` does).
--
-- SECRET fields land here too, encrypted when MOSAICAST_ENCRYPTION_KEY is set and in plain text when it is
-- not (the app warns once at boot and the admin page says so on every such field). ENV_SECRET fields never
-- reach this table at all — their value lives in the environment and is read straight from it. Do not
-- "fix" that omission by adding a column for them: a value that is never stored cannot leak from a backup.
CREATE TABLE external_provider_setting (
    kind        TEXT        NOT NULL,
    provider_id TEXT        NOT NULL,
    key         TEXT        NOT NULL,      -- a field declared in that provider's settings manifest
    value       JSONB       NOT NULL,      -- type-checked against the declared field before it gets here
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, provider_id, key)
);

CREATE INDEX idx_external_provider_setting_provider
    ON external_provider_setting (kind, provider_id);
