// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { progressEnabled } from '../consent/ConsentContext';

/**
 * Where the player stores a position; the key shape is disclosed as `mc.progress.*` (§12.5).
 *
 * The one definition. There were three — two helpers and an inline template in `buildCtx` — and the inline
 * one was the one that missed the "remember playback position" switch (core#201).
 */
export const progressKey = (episodeId: string) => `mc.progress.${episodeId}`;

/**
 * The position stored on this device for an episode, in seconds, or null.
 *
 * Null when the visitor switched remembering off, without consulting storage — the positions were deleted
 * then, and one written since by anything else is not one we may hand out. `ctx.progress.get` read the key
 * directly and still returned a position after the switch was off (core#201).
 */
export function storedPosition(episodeId: string): number | null {
  if (!progressEnabled()) {
    return null;
  }
  try {
    const raw = localStorage.getItem(progressKey(episodeId));
    if (raw == null) {
      return null;
    }
    const seconds = Number(raw);
    return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
  } catch {
    return null;
  }
}

/**
 * How far into an episode this device got, as `0…1`, or `0` when there is nothing worth drawing.
 *
 * Read synchronously from `localStorage` on purpose: a feed of thirty cards each awaiting a request would
 * be thirty requests to draw a 3px line, and the anonymous case — the common one — has no server position
 * to fetch anyway. The logged-in position still syncs through `PlayerContext`; this is the paint.
 *
 * Returns `0` when the visitor switched remembering off, without consulting storage: the positions are
 * deleted at that point, and a stale bar would be a claim about them we no longer have grounds to make.
 */
export function listenedFraction(episodeId: string, durationSeconds: number | null | undefined): number {
  if (!progressEnabled() || !durationSeconds || durationSeconds <= 0) {
    return 0;
  }
  try {
    const seconds = Number(localStorage.getItem(progressKey(episodeId)));
    if (!Number.isFinite(seconds) || seconds <= 0) {
      return 0;
    }
    const fraction = seconds / durationSeconds;
    // Under ~2% is indistinguishable from "opened it by accident", and a sliver of accent under every card
    // reads as decoration rather than as information.
    return fraction < 0.02 ? 0 : Math.min(1, fraction);
  } catch {
    return 0;
  }
}
