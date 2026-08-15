-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Podcaster-curated related episodes (ARCHITECTURE §6.3). The v1 related strategy is a weighted blend of
-- season, shared tags and fuzzy title — all derived, all occasionally wrong in ways only a human notices.
-- A pin is the override: "whatever the blend thinks, show this one here."
--
-- Directed, not symmetric. Pinning B under A says A's listeners should see B; it does not claim B's
-- listeners want A. A two-part episode is the obvious case where it is symmetric and the podcaster pins
-- both ways; a trailer pinned under every episode of its season is the case where it is not.
--
-- ON DELETE CASCADE on both sides: an EpisodeRef is never hard-deleted by the pipeline (§5.2 sets
-- WITHDRAWN instead), so a row only disappears when something has genuinely removed the episode — and a pin
-- to an episode that no longer exists is not worth keeping.

CREATE TABLE episode_pin (
    episode_ref_id UUID    NOT NULL REFERENCES episode_ref(id) ON DELETE CASCADE,
    related_ref_id UUID    NOT NULL REFERENCES episode_ref(id) ON DELETE CASCADE,
    position       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (episode_ref_id, related_ref_id),
    -- An episode pinned to itself would be filtered out on read anyway; refusing it at the source means the
    -- admin finds out when they do it rather than wondering why nothing appeared.
    CONSTRAINT ck_episode_pin_not_self CHECK (episode_ref_id <> related_ref_id)
);

-- Reads are always "the pins for this episode, in order".
CREATE INDEX idx_episode_pin_lookup ON episode_pin (episode_ref_id, position);
