// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * Whether audio is playing, for code that must not interrupt it — and a way to wait until it stops.
 *
 * It exists for one caller: a consent decision that changes the response CSP needs a full reload, since a
 * delivered document's policy cannot be changed, and a reload ends playback — the one thing the product
 * promises navigation never does (core#168). So the reload waits for the next pause instead, and the player's
 * now-playing record brings the bar back where it was.
 *
 * Module state rather than context on purpose: the consent provider sits *inside* the player provider, the
 * player reads consent state through a plain function already, and a context import in the other direction
 * would make the two modules import each other.
 */

let busy: () => boolean = () => false;
const idleWaiters = new Set<() => void>();

/**
 * Registers the player's "is anything playing?" answer. One player per page, so this replaces rather than
 * accumulates.
 *
 * @returns an unregister function, for the provider's unmount
 */
export function registerPlayback(isPlaying: () => boolean): () => void {
  busy = isPlaying;
  return () => {
    if (busy === isPlaying) {
      busy = () => false;
    }
  };
}

/** Whether something is audibly playing right now. */
export function isPlaybackBusy(): boolean {
  return busy();
}

/**
 * Runs `callback` once, the next time playback stops — pause, end, or the bar closed.
 *
 * @returns a cancel function
 */
export function whenPlaybackIdle(callback: () => void): () => void {
  idleWaiters.add(callback);
  return () => idleWaiters.delete(callback);
}

/** Called by the player when playback stops. */
export function notifyPlaybackIdle(): void {
  const waiting = [...idleWaiters];
  idleWaiters.clear();
  waiting.forEach((callback) => callback());
}
