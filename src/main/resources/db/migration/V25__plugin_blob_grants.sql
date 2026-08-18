-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Per-plugin storage grants, editable by an admin in the web UI (ARCHITECTURE §11.1).
--
-- §11.1 shipped with the limits configurable only through `mosaicast.plugin-blobs.*`, which is one number
-- for every plugin on the install and needs a restart to change. That is the wrong shape for the thing it
-- governs: a wiki accumulating diagrams and a bingo plugin storing nothing have no reason to share a
-- ceiling, and "the wiki has outgrown its space" is an ordinary operational event rather than a redeploy.
--
-- A row here is an explicit admin decision and **wins over the plugin's manifest**. That is the point: a
-- manifest's `blobs.quotaBytes` is what the plugin's author guessed the plugin would need, and an admin
-- looking at this install's actual usage knows better. Before this the effective quota was
-- min(manifest, operator), so an admin raising the ceiling past what the manifest asked for changed
-- nothing — the control would have appeared to work and silently done nothing.
--
-- Both columns are nullable and independently meaningful: an admin may raise the total without touching
-- the per-file cap, or the reverse. A NULL column means "no decision here", and that limit falls back to
-- the manifest's ask and then to the operator's default.
--
-- No FK on plugin_id: plugins live on disk, and a grant has to survive a plugin being removed and put back
-- (§7.8 — removal makes a plugin dormant, it does not discard what an admin decided about it). Same
-- reasoning as plugin_activation and plugin_config, which are keyed the same way.

CREATE TABLE plugin_blob_grant (
    plugin_id      TEXT        PRIMARY KEY,
    quota_bytes    BIGINT,
    max_file_bytes BIGINT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_plugin_blob_grant_positive
        CHECK ((quota_bytes IS NULL OR quota_bytes > 0)
           AND (max_file_bytes IS NULL OR max_file_bytes > 0))
);
