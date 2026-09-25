// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * What was playing, kept across a full page load (core#168).
 *
 * The player survived every client-side navigation and nothing else: F5, a typed URL, a deep link into a
 * plugin page and — worst — the reload a CSP-relevant consent decision needs all ended playback with the
 * episode and position simply gone. `mc.progress.<id>` remembered *where* in each episode, never *which*
 * episode was playing, so the position came back only if the listener found the episode again themselves.
 *
 * Part of "remember playback position": written only while that switch is on, removed when it goes off, and
 * disclosed with it (`CoreStorageInventory`). The switch's promise is that nothing about what someone listened
 * to is kept on the device once it is off, and this is exactly such a thing.
 *
 * Deliberately a leaf module — no imports from the consent code, so that code can clear the key without the
 * two modules importing each other. The switch is passed in by the caller instead.
 */

/** The single key; disclosed as `mc.nowplaying` in `CoreStorageInventory`. */
export const NOW_PLAYING_KEY = 'mc.nowplaying';

/** The subset of an episode the bar needs to render before anything has been fetched. */
export interface NowPlayingEpisode {
  id: string;
  slug: string;
  title: string;
  audioUrl?: string | null;
  imageUrl?: string | null;
  feedTitle?: string | null;
  season?: number | null;
  episodeNo?: number | null;
}

export interface NowPlaying {
  episode: NowPlayingEpisode;
  /** Seconds in, as the bar last showed it. */
  position: number;
  /** Seconds, or 0 when metadata had not loaded yet — the bar shows 0:00 rather than a guess. */
  duration: number;
}

const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0;
const optionalText = (value: unknown) => value == null || typeof value === 'string';
const optionalNumber = (value: unknown) => value == null || (typeof value === 'number' && Number.isFinite(value));
const seconds = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0);

/**
 * The stored record, or null — when remembering is off, nothing was stored, or what is stored is not a
 * record this version wrote. Storage is written by anything on the origin, so the shape is checked rather
 * than trusted: a malformed record must cost the restore, not the boot.
 */
export function readNowPlaying(remembering: boolean): NowPlaying | null {
  if (!remembering) {
    return null;
  }
  try {
    const raw = localStorage.getItem(NOW_PLAYING_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<NowPlaying> | null;
    const episode = parsed?.episode as Partial<NowPlayingEpisode> | undefined;
    if (!episode || !text(episode.id) || !text(episode.slug) || !text(episode.title)
      || !optionalText(episode.audioUrl) || !optionalText(episode.imageUrl) || !optionalText(episode.feedTitle)
      || !optionalNumber(episode.season) || !optionalNumber(episode.episodeNo)) {
      return null;
    }
    return {
      episode: {
        id: episode.id,
        slug: episode.slug,
        title: episode.title,
        audioUrl: episode.audioUrl ?? null,
        imageUrl: episode.imageUrl ?? null,
        feedTitle: episode.feedTitle ?? null,
        season: episode.season ?? null,
        episodeNo: episode.episodeNo ?? null,
      },
      position: seconds(parsed?.position),
      duration: seconds(parsed?.duration),
    };
  } catch {
    return null;
  }
}

/** Stores the record while remembering is on; a refused write costs the restore, nothing else. */
export function writeNowPlaying(record: NowPlaying, remembering: boolean): void {
  if (!remembering) {
    return;
  }
  try {
    localStorage.setItem(NOW_PLAYING_KEY, JSON.stringify(record));
  } catch {
    // Storage full or blocked: this page keeps playing, the next one starts without a bar.
  }
}

export function clearNowPlaying(): void {
  try {
    localStorage.removeItem(NOW_PLAYING_KEY);
  } catch {
    // Nothing stored that could be read either.
  }
}
