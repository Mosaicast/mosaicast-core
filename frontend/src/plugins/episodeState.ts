// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { EpisodePhase as SdkEpisodePhase, EpisodeStatus as SdkEpisodeStatus } from '@mosaicast/plugin-sdk';

/** `ctx.episode` as the SDK types it (platformApi 0.18.0). */
export interface PluginEpisodeState {
  status: SdkEpisodeStatus;
  phase: SdkEpisodePhase;
  announceAt?: string;
}

/**
 * An episode's release state in the SDK's spelling (core#252).
 *
 * The phase comes from the host where it has one — the episode page's detail carries it — and is derived from
 * the status otherwise. That derivation is exact for the public lists cards are drawn from: a quiet planned
 * episode never appears in one, so a `PLANNED` card is always an announced, upcoming one.
 */
export function pluginEpisodeState(
  status: string | null | undefined,
  phase?: string | null,
  announceAt?: string | null,
): PluginEpisodeState | undefined {
  if (status !== 'PLANNED' && status !== 'PUBLISHED' && status !== 'WITHDRAWN') {
    return undefined;
  }
  const derived: SdkEpisodePhase =
    status === 'PUBLISHED' ? 'released' : status === 'WITHDRAWN' ? 'withdrawn' : 'upcoming';
  const given = phase?.toLowerCase();
  const state: PluginEpisodeState = {
    status,
    phase:
      given === 'planned' || given === 'upcoming' || given === 'released' || given === 'withdrawn' ? given : derived,
  };
  if (announceAt && status === 'PLANNED') {
    state.announceAt = announceAt;
  }
  return state;
}
