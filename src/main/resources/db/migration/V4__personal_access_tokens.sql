-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Podcaster-scoped personal access tokens for automation (ARCHITECTURE §8.5, e.g. stats/MAT upload).
-- Only the SHA-256 hash is stored; the plaintext token is shown once at creation and never again.

CREATE TABLE personal_access_token (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,          -- a human label for the token
    token_hash   VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of the secret
    prefix       VARCHAR(16) NOT NULL,          -- leading chars, shown in the UI for identification
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_pat_user ON personal_access_token (user_id);
