-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- A planned episode can be prepared quietly and made public later (core#252). `announce_at` is the moment a
-- PLANNED episode becomes visible to everyone as "Upcoming"; empty means it stays quiet — seen only by
-- podcasters and admins — until someone announces it. Whether it has passed is decided at read time, so no
-- scheduler flips anything. A published episode is public whatever this says: the feed wins.
ALTER TABLE episode_ref ADD COLUMN announce_at TIMESTAMPTZ;

-- Every planned episode that exists was created public, because quiet did not exist. It stays public.
UPDATE episode_ref SET announce_at = first_seen_at WHERE status = 'PLANNED';
