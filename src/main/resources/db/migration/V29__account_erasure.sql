-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Account deletion, and the debt it leaves behind (ARCHITECTURE §12, SDK 0.9.0 `UserDataHandler`).
--
-- Core can drop what it owns — identities, tokens, listening progress, the USER-scope documents the host
-- holds on a plugin's behalf. It cannot touch a plugin's own schema columns or blobs: it provisioned those
-- tables without ever learning which column is a person, and it cannot know that pseudonymising is the
-- right answer where deleting is not. So each plugin is asked, and each answer is recorded.
--
-- The row is the point. A handler that throws, or a plugin that is installed but switched off when the
-- deletion runs, would otherwise be a silent skip: the account disappears, the person is told it is done,
-- and their data is still there. An unfinished row survives a restart, is retried, and is visible in admin.
--
-- The user id is kept deliberately after the account row is gone: it is what a handler needs to find the
-- rows it must erase, and it no longer resolves to a person here.

CREATE TABLE user_data_erasure (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL,
    plugin_id   TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, plugin_id)
);

-- The retry sweep reads exactly this: what is not done yet.
CREATE INDEX idx_user_data_erasure_open ON user_data_erasure (status) WHERE status <> 'DONE';
