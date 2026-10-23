-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Visitor feedback (ARCHITECTURE §20): reports go to the operator, never upstream. A report with a user is that
-- user's data — erased with the account; one without has no one to erase it for and is purged once closed.
CREATE TABLE feedback (
    id          UUID        PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('problem', 'idea', 'other')),
    message     TEXT        NOT NULL,
    context     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    user_id     UUID        REFERENCES app_user (id) ON DELETE CASCADE,
    -- Only for anonymous senders, for a reply and nothing else; cleared when the report is closed (§20.2).
    contact     TEXT,
    locale      TEXT,
    status      TEXT        NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'seen', 'done', 'spam')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled_by  UUID        REFERENCES app_user (id) ON DELETE SET NULL,
    handled_at  TIMESTAMPTZ,
    replied_at  TIMESTAMPTZ,
    -- Hash of the normalised message, so an identical report within the dedup window is stored once (§20.4).
    dedup_hash  TEXT        NOT NULL
);
CREATE INDEX feedback_status_created ON feedback (status, created_at DESC);
CREATE INDEX feedback_dedup ON feedback (dedup_hash, created_at);
CREATE INDEX feedback_user ON feedback (user_id) WHERE user_id IS NOT NULL;

-- Who may send and who reads (§20.1), admin-only settings.
ALTER TABLE site_config ADD COLUMN feedback_mode  VARCHAR(16) NOT NULL DEFAULT 'EVERYONE';
ALTER TABLE site_config ADD COLUMN feedback_url   TEXT;
ALTER TABLE site_config ADD COLUMN feedback_floor VARCHAR(16) NOT NULL DEFAULT 'ADMIN';

-- A signed-in user a reader blocked from sending (§20.4). Who did it is in the log, not a column, like a role
-- change (§8.5), so erasure has nothing extra to chase.
ALTER TABLE app_user ADD COLUMN feedback_blocked_at TIMESTAMPTZ;
