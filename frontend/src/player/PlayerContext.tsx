// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';

import { api } from '../api/client';
import type { EpisodeDetail, EpisodeSummary } from '../api/types';
import { PlayerBar } from './PlayerBar';

/**
 * The persistent player (ARCHITECTURE §6.2/§6.5): one global `<audio>` that survives route changes,
 * **auto-advances** to the next episode in the feed sequence when one ends, wires the **Media Session API**
 * (lock-screen / hardware controls), and persists **listening progress** to localStorage (server-side sync
 * for logged-in users lands in E4c). The bar renders into the `player` slot region.
 */

/** The minimum a caller needs to start playback; the audio URL is fetched (detail-only) when absent. */
export interface PlayableEpisode {
  id: string;
  title: string;
  audioUrl?: string | null;
  imageUrl?: string | null;
  feedTitle?: string | null;
  season?: number | null;
  episodeNo?: number | null;
}

interface PlayerContextValue {
  current: PlayableEpisode | null;
  playing: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  play: (episode: PlayableEpisode) => void;
  toggle: () => void;
  seek: (seconds: number) => void;
  setVolume: (v: number) => void;
}

const PlayerContext = createContext<PlayerContextValue | null>(null);

export function usePlayer(): PlayerContextValue {
  const ctx = useContext(PlayerContext);
  if (!ctx) {
    throw new Error('usePlayer must be used within a PlayerProvider');
  }
  return ctx;
}

const progressKey = (id: string) => `mc.progress.${id}`;

export function PlayerProvider({ children }: { children: ReactNode }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const pendingSeekRef = useRef<number>(0);
  const [current, setCurrent] = useState<PlayableEpisode | null>(null);
  const [playing, setPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolumeState] = useState(1);

  const play = useCallback(
    async (episode: PlayableEpisode) => {
      const audio = audioRef.current;
      if (!audio) {
        return;
      }
      // Resolve the audio URL (summaries omit it — detail only) if the caller didn't provide it.
      let url = episode.audioUrl ?? null;
      if (!url) {
        try {
          url = (await api.get<EpisodeDetail>(`/api/episodes/${episode.id}`)).audioUrl;
        } catch {
          url = null;
        }
      }
      if (!url) {
        return; // nothing playable (e.g. an upcoming episode with no audio yet)
      }
      if (current?.id !== episode.id) {
        audio.src = url;
        pendingSeekRef.current = Number(localStorage.getItem(progressKey(episode.id)) ?? 0);
        setCurrent({ ...episode, audioUrl: url });
      }
      try {
        await audio.play();
      } catch {
        /* autoplay may be blocked until a user gesture; the bar's play button retries */
      }
    },
    [current],
  );

  const toggle = useCallback(() => {
    const audio = audioRef.current;
    if (!audio || !current) {
      return;
    }
    if (audio.paused) {
      void audio.play();
    } else {
      audio.pause();
    }
  }, [current]);

  const seek = useCallback((seconds: number) => {
    const audio = audioRef.current;
    if (audio) {
      audio.currentTime = Math.max(0, seconds);
    }
  }, []);

  const setVolume = useCallback((v: number) => {
    const audio = audioRef.current;
    const clamped = Math.min(1, Math.max(0, v));
    if (audio) {
      audio.volume = clamped;
    }
    setVolumeState(clamped);
  }, []);

  // Auto-advance to the next episode in the feed sequence when the current one ends (§6.2).
  const advance = useCallback(async () => {
    if (!current) {
      return;
    }
    try {
      const adjacent = await api.get<{ next: EpisodeSummary | null }>(`/api/episodes/${current.id}/adjacent`);
      const next = adjacent.next;
      if (next) {
        void play({
          id: next.id,
          title: next.title,
          imageUrl: next.imageUrl,
          season: next.season,
          episodeNo: next.episodeNo,
        });
      }
    } catch {
      /* end of feed or offline — just stop */
    }
  }, [current, play]);

  // Wire the single <audio> element's events once.
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    const onLoaded = () => {
      setDuration(audio.duration || 0);
      if (pendingSeekRef.current > 0) {
        audio.currentTime = pendingSeekRef.current;
        pendingSeekRef.current = 0;
      }
    };
    const onTime = () => {
      setCurrentTime(audio.currentTime);
      if (current) {
        // Persist progress (throttle to whole seconds to avoid churn).
        localStorage.setItem(progressKey(current.id), String(Math.floor(audio.currentTime)));
      }
    };
    const onEnded = () => {
      if (current) {
        localStorage.removeItem(progressKey(current.id));
      }
      void advance();
    };
    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('loadedmetadata', onLoaded);
    audio.addEventListener('timeupdate', onTime);
    audio.addEventListener('ended', onEnded);
    return () => {
      audio.removeEventListener('play', onPlay);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('loadedmetadata', onLoaded);
      audio.removeEventListener('timeupdate', onTime);
      audio.removeEventListener('ended', onEnded);
    };
  }, [current, advance]);

  // Media Session API: metadata + hardware/lock-screen controls.
  useEffect(() => {
    if (!('mediaSession' in navigator)) {
      return;
    }
    if (current) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: current.title,
        artwork: current.imageUrl ? [{ src: current.imageUrl }] : undefined,
      });
      navigator.mediaSession.setActionHandler('play', () => toggle());
      navigator.mediaSession.setActionHandler('pause', () => toggle());
      navigator.mediaSession.setActionHandler('seekbackward', () => seek(currentTime - 15));
      navigator.mediaSession.setActionHandler('seekforward', () => seek(currentTime + 30));
      navigator.mediaSession.setActionHandler('nexttrack', () => void advance());
    }
  }, [current, currentTime, toggle, seek, advance]);

  const value: PlayerContextValue = {
    current,
    playing,
    currentTime,
    duration,
    volume,
    play,
    toggle,
    seek,
    setVolume,
  };

  return (
    <PlayerContext.Provider value={value}>
      {children}
      {/* One audio element for the whole app; the bar shows once something is loaded. */}
      <audio ref={audioRef} preload="metadata" />
      {current && <PlayerBar />}
    </PlayerContext.Provider>
  );
}
