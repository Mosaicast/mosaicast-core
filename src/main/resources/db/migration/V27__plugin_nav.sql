-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Admin decisions about the shell's navigation menu (ARCHITECTURE §7.3).
--
-- A plugin declares its entrances in its manifest; this table holds only what an ADMIN has decided about
-- them — whether to show one, and where it sits. Same shape and same reasoning as `plugin_activation`
-- (V14): an absent row means "enabled, at the order the manifest asked for", so the table stays empty on an
-- install where nobody has overridden anything, and a row is always a deliberate human decision rather than
-- a default someone has to recognise as untouched.
--
-- Keyed by (plugin_id, path) because that pair IS the destination. A row for an entry a plugin no longer
-- declares is inert rather than an error, exactly as a stale `plugin_config` row is: resolution starts from
-- the manifest, so an override with nothing to override can never surface. Deleting the plugin's rows on
-- uninstall is a tidy-up, not a correctness requirement.
--
-- `path` is the subpath below /p/{plugin_id}/ — empty string for the plugin's own root, which is why the
-- column is NOT NULL with no length floor rather than nullable: '' is a real destination, not a missing one.
CREATE TABLE plugin_nav_override (
    plugin_id  VARCHAR(64)  NOT NULL,
    path       VARCHAR(256) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id, path)
);

-- The menu reads every override for every plugin on each resolve, ordered. Small table, but the index makes
-- the ordering free and mirrors `idx_legal_page_sort`, the other "admin ordered a short list" case.
CREATE INDEX idx_plugin_nav_sort ON plugin_nav_override (sort_order);
