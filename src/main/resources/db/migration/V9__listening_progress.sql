-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Per-user listening progress (ARCHITECTURE §6.5): the player's resume position per episode for logged-in
-- users (anonymous users keep this in localStorage only). One source of truth for "has this user heard it".

CREATE TABLE listening_progress (
    user_id          UUID    NOT NULL REFERENCES app_user(id)    ON DELETE CASCADE,
    episode_ref_id   UUID    NOT NULL REFERENCES episode_ref(id) ON DELETE CASCADE,
    position_seconds INTEGER NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, episode_ref_id)
);
