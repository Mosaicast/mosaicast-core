-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The GDPR data export (ARCHITECTURE §12.8.1, core#263): one row per requested archive, and one per plugin
-- asked for its part — written before it is asked, so a plugin that throws or cannot be asked is a recorded
-- outcome, never a silence. The archive itself is a blob in the `export` namespace; erasure deletes both.
CREATE TABLE user_data_export (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status       TEXT        NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    blob_id      UUID,
    size_bytes   BIGINT,
    error        TEXT
);
CREATE INDEX idx_user_data_export_user ON user_data_export (user_id, requested_at DESC);
CREATE INDEX idx_user_data_export_status ON user_data_export (status);

CREATE TABLE user_data_export_part (
    id        UUID   PRIMARY KEY,
    export_id UUID   NOT NULL REFERENCES user_data_export(id) ON DELETE CASCADE,
    plugin_id TEXT   NOT NULL,
    outcome   TEXT   NOT NULL,
    detail    TEXT,
    bytes     BIGINT,
    UNIQUE (export_id, plugin_id)
);
