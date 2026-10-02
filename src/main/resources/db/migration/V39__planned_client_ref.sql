-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- An automation that plans episodes through the API can name each plan with its own reference, so retrying
-- a request that timed out returns the episode it already created instead of planning a second one
-- (core#252). Unique per feed, and only where one was given.
ALTER TABLE episode_ref ADD COLUMN client_ref TEXT;
CREATE UNIQUE INDEX uq_episode_ref_feed_client_ref ON episode_ref (feed_id, client_ref) WHERE client_ref IS NOT NULL;
