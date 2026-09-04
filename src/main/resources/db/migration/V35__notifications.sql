-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- One inbox, three senders (ARCHITECTURE §17).
--
-- Three things needed to tell a user something and none of them could: an admin reverting a display name
-- (§8.6.1) changed it silently, which reads as a break-in; an admin had no way to warn somebody short of
-- removing them; and a plugin that resolves something a user took part in could only hope they came back
-- and looked.
--
-- `source` is `system`, `admin`, or `plugin:<id>` — a string rather than an enum because the third form
-- carries an id, and because a plugin's rows have to be findable by prefix when that plugin's data goes
-- (§17.2). `kind` is the message type for `system` rows (the shell translates it) and null otherwise.
--
-- `payload` is JSONB rather than a text column because what a notification says depends on who sent it: a
-- system row carries parameters for a key the shell owns, an admin row carries the warning text, and a
-- plugin row carries one sentence per locale (§17.1) — the plugin cannot know which language the reader
-- will have when they open it days later.

CREATE TABLE notification (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    source     TEXT        NOT NULL,
    kind       TEXT,
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    link       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);

-- The inbox query: one user's notifications, newest first. Partial-index the unread ones because the bell
-- asks "how many unread" on every page load and that count should not walk a user's whole history.
CREATE INDEX ix_notification_user      ON notification(user_id, created_at DESC);
CREATE INDEX ix_notification_unread    ON notification(user_id) WHERE read_at IS NULL;
-- Retention sweeps by age, and a plugin's rows go when its data does (§17.2).
CREATE INDEX ix_notification_read_at   ON notification(read_at) WHERE read_at IS NOT NULL;
CREATE INDEX ix_notification_source    ON notification(source);
