// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import { api } from '../api/client';
import { progressEnabled } from '../consent/ConsentContext';
import type { EpisodeDetail, EpisodeSummary } from '../api/types';
import { useUser } from '../auth/UserContext';
import { clearNowPlaying, readNowPlaying, writeNowPlaying } from './nowPlaying';
import { notifyPlaybackIdle, registerPlayback } from './playbackGate';
import { PlayerBar } from './PlayerBar';

/**
 * The persistent player (ARCHITECTURE §6.2/§6.5): one global `<audio>` that survives route changes,
 * **auto-advances** to the next episode in the feed sequence when one ends, wires the **Media Session API**
 * (lock-screen / hardware controls), and persists **listening progress** to localStorage (server-side sync
 * for logged-in users lands in E4c). The bar renders into the `player` slot region.
 *
 * It also survives a **full page load** (core#168): what was playing is kept as a now-playing record and the
 * bar comes back, paused, at the same position. Deliberately without loading the audio — pointing the element
 * at a third-party enclosure on boot is a request to that host the visitor did not make on this page view, so
 * the source is only set when they press play.
 */

/** The minimum a caller needs to start playback; the audio URL is fetched (detail-only) when absent. */
export interface PlayableEpisode {
  id: string; // internal UUID — progress + local key
  slug: string; // public slug — episode API + detail URL
  title: string;
  audioUrl?: string | null;
  imageUrl?: string | null;
  feedTitle?: string | null;
  season?: number | null;
  episodeNo?: number | null;
}

/**
 * Why nothing is playing when something should be — the two states the bar used to render as a dead,
 * paused player with no explanation (core#170).
 *
 * - `error`: the element could not load or decode the source — a moved enclosure, a host outage, a codec it
 *   refuses. The bar offers a retry.
 * - `blocked`: the browser refused to start audio without a gesture, which is what a `?t=` deep link always
 *   meets. The bar asks for a press of play, which is the gesture it wanted.
 */
export type PlaybackProblem = 'error' | 'blocked';

/**
 * What the player *is* right now. Every field here moves while audio plays — `currentTime` about four
 * times a second — so a component that reads this re-renders at that rate. That is correct for the bar
 * and wrong for everything else, which is why the actions live in their own context below.
 */
interface PlayerStateValue {
  current: PlayableEpisode | null;
  playing: boolean;
  currentTime: number;
  duration: number;
  volume: number;
  /** Playback speed. Table stakes for a podcast player, and remembered across episodes and reloads. */
  rate: number;
  problem: PlaybackProblem | null;
}

/**
 * What a component can *do* to the player. Separate from the state above because the two change at wildly
 * different rates: a Play button on an episode card needs `play` and nothing else, and subscribing it to
 * the whole player meant re-rendering all forty cards on a feed page several times a second — which then
 * rebuilt the `ctx` of every plugin mounted under them and re-ran their fetches (core#158).
 *
 * This value's identity never changes: every action reads what it needs through refs.
 */
interface PlayerActionsValue {
  /**
   * Starts (or resumes) an episode. `startAt` is the position a **shared timestamped link** asked for
   * (§6.4); it wins over the stored listening position for that navigation, and until playback actually
   * advances past it nothing is written back (§6.5).
   *
   * Resolves once playback has started or been refused; a refusal is reported through `problem`, never
   * thrown. When calls overlap, the last one wins, whatever order their requests come back in.
   */
  play: (episode: PlayableEpisode, opts?: { startAt?: number }) => Promise<void>;
  toggle: () => void;
  seek: (seconds: number) => void;
  /** Jumps relative to the current position; negative goes back. Clamped to the episode. */
  skip: (seconds: number) => void;
  setVolume: (v: number) => void;
  setRate: (rate: number) => void;
  /** Reloads the current source after an error, from where it stopped. */
  retry: () => void;
  /** Stops, unloads the audio and closes the bar. */
  stop: () => void;
  /**
   * The live position *without* subscribing to it. For callers that need to read the position when
   * something happens rather than to render it — a plugin's `ctx.player.currentTime()`, a share link being
   * built. Reading it through the state context instead is what made those callers tick-rate consumers.
   */
  getCurrentTime: () => number;
}

/** The whole player, for the one component that renders all of it. */
interface PlayerContextValue extends PlayerStateValue, PlayerActionsValue {}

/** The speeds the bar offers, in the order it cycles through them. */
export const PLAYBACK_RATES = [1, 1.25, 1.5, 1.75, 2] as const;

const RATE_KEY = 'mc.prefs.rate';
const VOLUME_KEY = 'mc.prefs.volume';

/**
 * Playback speed is a listener preference, not an episode one — someone who listens at 1.5× listens to
 * everything at 1.5×, and having to set it again each episode is the kind of small friction that makes a
 * player feel unfinished. Stored under `mc.prefs.*` alongside the playback-position switch, and disclosed
 * with it (see `CoreStorageInventory`).
 */
function storedRate(): number {
  try {
    const saved = Number(localStorage.getItem(RATE_KEY));
    return PLAYBACK_RATES.includes(saved as (typeof PLAYBACK_RATES)[number]) ? saved : 1;
  } catch {
    return 1;
  }
}

/** The same argument as the rate: it was reset to full on every page load, while the speed was kept. */
function storedVolume(): number {
  try {
    const raw = localStorage.getItem(VOLUME_KEY);
    const saved = raw == null ? NaN : Number(raw);
    return Number.isFinite(saved) && saved >= 0 && saved <= 1 ? saved : 1;
  } catch {
    return 1;
  }
}

const PlayerStateContext = createContext<PlayerStateValue | null>(null);
const PlayerActionsContext = createContext<PlayerActionsValue | null>(null);

/**
 * The player's state and its actions together — for a component that renders the position, such as the bar.
 * A caller that only ever *acts* on the player should use {@link usePlayerActions}: this hook re-renders
 * its caller on every `timeupdate`.
 */
export function usePlayer(): PlayerContextValue {
  const state = useContext(PlayerStateContext);
  const actions = useContext(PlayerActionsContext);
  if (!state || !actions) {
    throw new Error('usePlayer must be used within a PlayerProvider');
  }
  return useMemo(() => ({ ...state, ...actions }), [state, actions]);
}

/**
 * The player's actions, without subscribing to its state. A Play button, a deep link that seeks, a plugin
 * mount — none of them renders the position, and none of them should re-render four times a second.
 */
export function usePlayerActions(): PlayerActionsValue {
  const actions = useContext(PlayerActionsContext);
  if (!actions) {
    throw new Error('usePlayerActions must be used within a PlayerProvider');
  }
  return actions;
}

const progressKey = (id: string) => `mc.progress.${id}`;

/**
 * How far playback must advance past a shared timestamp before the position is remembered again.
 *
 * Following a link to `12:04` is a request to hear *that spot*, not a statement about where the listener
 * got to — so writing it straight to their stored progress would silently move their place in an episode
 * they were halfway through. A few seconds of actual playback is the difference between "looked" and
 * "listened", and it is what turns the seek back into an ordinary position worth keeping.
 */
const PROGRESS_ADVANCE_SECONDS = 5;

/**
 * Tells the server a listener's browser could not play an episode, so the operator sees it under Logs &
 * health rather than only the listener seeing a dead bar (core#170). Once per episode per page view: the
 * server de-duplicates across listeners as well, this only keeps one visitor's retries from each costing a
 * request.
 */
const reported = new Set<string>();
function reportUnplayable(episode: PlayableEpisode, code: number) {
  if (reported.has(episode.id)) {
    return;
  }
  reported.add(episode.id);
  void api.post(`/api/episodes/${encodeURIComponent(episode.slug)}/playback-error`, { code }).catch(() => {});
}

/** Lock-screen position, when the browser has one and the duration is known. */
function updatePositionState(audio: HTMLAudioElement) {
  const session = 'mediaSession' in navigator ? navigator.mediaSession : null;
  const duration = audio.duration;
  if (!session?.setPositionState || !Number.isFinite(duration) || duration <= 0) {
    return;
  }
  try {
    session.setPositionState({
      duration,
      playbackRate: audio.playbackRate || 1,
      position: Math.min(Math.max(0, audio.currentTime), duration),
    });
  } catch {
    // A browser that rejects the numbers keeps its own idea of the position; nothing to show the listener.
  }
}

export function PlayerProvider({ children }: { children: ReactNode }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  // Read once: what was playing before this page load, if the listener lets us remember it.
  const [restored] = useState(() => readNowPlaying(progressEnabled()));
  const pendingSeekRef = useRef<number>(restored?.position ?? 0);
  // Set while a shared timestamp is being honoured; progress writes are suppressed below it (§6.5).
  const progressFloorRef = useRef<number | null>(null);
  // Whether the viewer is logged in — read via a ref so the audio-event handlers don't re-subscribe on
  // login state changes, and the server progress write is throttled to avoid a PUT every second (§6.5).
  const { user } = useUser();
  const userRef = useRef(user);
  userRef.current = user;
  const lastServerWriteRef = useRef(0);
  // Which `play()` call owns the element. `play` awaits — for the detail that carries the audio URL, then
  // for the stored position — so two quick presses (two cards, or a card and a `?t=` deep link) ran
  // concurrently and the slower response won, with the bar showing one episode and the element loading
  // another. Every call takes a number; one that finds a newer number when it wakes up gives way.
  const loadSeqRef = useRef(0);
  const [current, setCurrentState] = useState<PlayableEpisode | null>(restored?.episode ?? null);
  // The same episode, readable from the audio handlers and the actions without depending on it.
  const currentRef = useRef<PlayableEpisode | null>(restored?.episode ?? null);
  const [playing, setPlaying] = useState(false);
  const [problem, setProblemState] = useState<PlaybackProblem | null>(null);
  const problemRef = useRef<PlaybackProblem | null>(null);
  const [currentTime, setCurrentTime] = useState(restored?.position ?? 0);
  // The same number as `currentTime`, kept where reading it costs no subscription: `getCurrentTime()` below
  // hands it to callers that need the position at a moment rather than on every frame of it.
  const currentTimeRef = useRef(restored?.position ?? 0);
  const [duration, setDuration] = useState(restored?.duration ?? 0);
  const durationRef = useRef(restored?.duration ?? 0);
  const [volume, setVolumeState] = useState(storedVolume);
  const [rate, setRateState] = useState(storedRate);
  // Read through a ref inside the audio-event handlers: they are subscribed once, and adding `rate` to that
  // effect's deps would tear the listeners down and rebuild them on every speed change.
  const rateRef = useRef(rate);
  rateRef.current = rate;

  const setCurrent = useCallback((episode: PlayableEpisode | null) => {
    currentRef.current = episode;
    setCurrentState(episode);
  }, []);

  const setProblem = useCallback((next: PlaybackProblem | null) => {
    problemRef.current = next;
    setProblemState(next);
  }, []);

  const setPosition = useCallback((seconds: number) => {
    currentTimeRef.current = seconds;
    setCurrentTime(seconds);
  }, []);

  /** Keeps the now-playing record in step with the bar. */
  const saveNowPlaying = useCallback(() => {
    const episode = currentRef.current;
    if (episode) {
      writeNowPlaying(
        { episode, position: Math.floor(currentTimeRef.current), duration: Math.floor(durationRef.current) },
        progressEnabled(),
      );
    }
  }, []);

  /**
   * `audio.play()`, with its refusal turned into something the bar can say.
   *
   * It used to be caught with an empty block, so a browser's autoplay refusal — which every `?t=` deep link
   * meets, since it starts from an effect rather than a click — left a paused bar and no explanation.
   * `AbortError` is a newer load replacing this one and says nothing; a source that cannot be played
   * arrives through the element's `error` event, handled there.
   */
  const start = useCallback(
    async (seq: number) => {
      const audio = audioRef.current;
      if (!audio) {
        return;
      }
      try {
        await audio.play();
      } catch (error) {
        if (seq === loadSeqRef.current && (error as DOMException | null)?.name === 'NotAllowedError') {
          setProblem('blocked');
        }
      }
    },
    [setProblem],
  );

  /** Where to resume an episode that is not loaded: server-side for a signed-in listener, else local. */
  const storedPosition = useCallback(async (episode: PlayableEpisode) => {
    // Nothing to restore once the visitor switched remembering off — the stored positions are gone. The
    // switch has to gate the server read too, not only the local one.
    //
    // It used to zero the position behind progressEnabled() and then overwrite it unconditionally from
    // /api/me/progress, so a signed-in listener who switched remembering off still resumed exactly where they
    // left off — the setting appeared to do nothing, and the rows it claimed to have deleted were still
    // there. For an anonymous visitor it worked, which is the worst version: the promise held for the
    // people with the least at stake.
    if (!progressEnabled()) {
      return 0;
    }
    let saved = 0;
    try {
      saved = Number(localStorage.getItem(progressKey(episode.id)) ?? 0) || 0;
    } catch {
      saved = 0;
    }
    if (userRef.current) {
      try {
        const map = await api.get<Record<string, number>>(`/api/me/progress?episodeIds=${episode.id}`);
        if (map[episode.id] != null) {
          saved = map[episode.id];
        }
      } catch {
        /* fall back to the localStorage value */
      }
    }
    return saved;
  }, []);

  const play = useCallback(
    async (episode: PlayableEpisode, opts?: { startAt?: number }) => {
      const audio = audioRef.current;
      if (!audio) {
        return;
      }
      const seq = ++loadSeqRef.current;
      setProblem(null);
      const startAt = opts?.startAt;
      // Resolve the audio URL (summaries omit it — detail only) if the caller didn't provide it.
      let url = episode.audioUrl ?? null;
      if (!url) {
        try {
          // The episode API is keyed by the public slug; progress below stays keyed by the UUID.
          url = (await api.get<EpisodeDetail>(`/api/episodes/${episode.slug}`)).audioUrl;
        } catch {
          url = null;
        }
      }
      if (seq !== loadSeqRef.current || !url) {
        return; // overtaken by a newer play(), or nothing playable (an upcoming episode with no audio yet)
      }

      const loaded = currentRef.current?.id === episode.id && audio.getAttribute('src') != null;
      if (loaded) {
        // Already the loaded episode, so metadata is there and the deferred seek would never fire — move
        // now instead. Nothing else changes: re-playing the current episode has never reset its position.
        if (startAt != null) {
          progressFloorRef.current = startAt;
          audio.currentTime = Math.max(0, startAt);
        }
      } else {
        // An explicit timestamp wins over the stored position for this navigation — someone following a
        // shared link asked for that spot, so the stored position is not even read.
        const resumeAt = startAt ?? (await storedPosition(episode));
        if (seq !== loadSeqRef.current) {
          return;
        }
        // Only now is the element pointed at this episode. Doing it before the awaits let a slower play()
        // for an episode the listener had already moved on from overwrite the source.
        audio.src = url;
        progressFloorRef.current = startAt ?? null;
        pendingSeekRef.current = Math.max(0, resumeAt);
        setPosition(Math.max(0, resumeAt));
        durationRef.current = 0;
        setDuration(0);
        setCurrent({ ...episode, audioUrl: url });
        saveNowPlaying();
      }
      await start(seq);
    },
    [setProblem, storedPosition, setPosition, setCurrent, saveNowPlaying, start],
  );

  const retry = useCallback(() => {
    const audio = audioRef.current;
    const episode = currentRef.current;
    if (!audio || !episode?.audioUrl) {
      return;
    }
    setProblem(null);
    // From where it stopped, which for a source that never loaded is where the bar says it is.
    pendingSeekRef.current = currentTimeRef.current;
    // Assigning `src` runs the element's load algorithm again, even for the same URL.
    audio.src = episode.audioUrl;
    void start(loadSeqRef.current);
  }, [setProblem, start]);

  const toggle = useCallback(() => {
    const audio = audioRef.current;
    const episode = currentRef.current;
    if (!audio || !episode) {
      return;
    }
    if (problemRef.current === 'error' || audio.getAttribute('src') == null) {
      // After an error, or for a bar restored from the last page load with nothing loaded yet: this press is
      // the one that fetches the audio, from the position the bar is showing.
      if (episode.audioUrl) {
        retry();
      } else {
        void play(episode);
      }
      return;
    }
    if (audio.paused) {
      setProblem(null);
      void start(loadSeqRef.current);
    } else {
      audio.pause();
    }
  }, [retry, play, setProblem, start]);

  const seek = useCallback(
    (seconds: number) => {
      const audio = audioRef.current;
      // Scrubbing by hand is a deliberate statement about where this listener is, so it ends the hold a
      // shared timestamp had on their stored position.
      progressFloorRef.current = null;
      const target = Math.max(0, seconds);
      if (audio && audio.getAttribute('src') != null) {
        audio.currentTime = target;
      } else {
        // Nothing loaded yet (a restored bar): the position is applied when the source is.
        pendingSeekRef.current = target;
        setPosition(target);
        saveNowPlaying();
      }
    },
    [setPosition, saveNowPlaying],
  );

  /** ±15/30 s, the jumps a podcast listener actually reaches for (BRIEF E4). */
  const skip = useCallback(
    (seconds: number) => {
      const audio = audioRef.current;
      const max = audio && Number.isFinite(audio.duration) && audio.duration > 0
        ? audio.duration
        : durationRef.current || Infinity;
      seek(Math.min(Math.max(0, currentTimeRef.current + seconds), max));
    },
    [seek],
  );

  const setRate = useCallback((next: number) => {
    const audio = audioRef.current;
    if (audio) {
      audio.playbackRate = next;
    }
    setRateState(next);
    try {
      localStorage.setItem(RATE_KEY, String(next));
    } catch {
      // A visitor blocking storage still gets the speed for this session.
    }
  }, []);

  const setVolume = useCallback((v: number) => {
    const audio = audioRef.current;
    const clamped = Math.min(1, Math.max(0, v));
    if (audio) {
      audio.volume = clamped;
    }
    setVolumeState(clamped);
    try {
      localStorage.setItem(VOLUME_KEY, String(clamped));
    } catch {
      // Same as the rate: kept for this page view.
    }
  }, []);

  const stop = useCallback(() => {
    const audio = audioRef.current;
    // Anything still loading belongs to the episode being closed.
    loadSeqRef.current++;
    setCurrent(null);
    if (audio) {
      audio.pause();
      // Unloads rather than just pausing: an element left pointing at the enclosure keeps a connection to
      // its host, for a bar the listener has closed.
      audio.removeAttribute('src');
      audio.load();
    }
    pendingSeekRef.current = 0;
    progressFloorRef.current = null;
    setPosition(0);
    durationRef.current = 0;
    setDuration(0);
    setPlaying(false);
    setProblem(null);
    clearNowPlaying();
    notifyPlaybackIdle();
  }, [setCurrent, setPosition, setProblem]);

  // Auto-advance to the next episode in the feed sequence when the current one ends (§6.2).
  const advance = useCallback(async () => {
    const episode = currentRef.current;
    if (!episode) {
      return;
    }
    try {
      const adjacent = await api.get<{ next: EpisodeSummary | null }>(`/api/episodes/${episode.slug}/adjacent`);
      const next = adjacent.next;
      if (next) {
        await play({
          id: next.id,
          slug: next.slug,
          title: next.title,
          imageUrl: next.imageUrl,
          feedTitle: episode.feedTitle,
          season: next.season,
          episodeNo: next.episodeNo,
        });
      } else {
        // The end of the feed: nothing is playing any more, and a consent reload that was waiting can go.
        notifyPlaybackIdle();
      }
    } catch {
      notifyPlaybackIdle(); /* offline — just stop */
    }
  }, [play]);

  // Something else (a consent decision) may need to know whether audio is playing before it reloads.
  useEffect(() => registerPlayback(() => Boolean(audioRef.current && !audioRef.current.paused)), []);

  // Wire the single <audio> element's events once.
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) {
      return;
    }
    const session = 'mediaSession' in navigator ? navigator.mediaSession : null;
    const onPlay = () => {
      setPlaying(true);
      setProblem(null);
      if (session) {
        session.playbackState = 'playing';
      }
      updatePositionState(audio);
    };
    const onPause = () => {
      setPlaying(false);
      if (session) {
        session.playbackState = 'paused';
      }
      saveNowPlaying();
      // A pause at the end of an episode is followed by auto-advance, not by silence — that is not idle.
      if (!audio.ended) {
        notifyPlaybackIdle();
      }
    };
    const onLoaded = () => {
      const length = Number.isFinite(audio.duration) ? audio.duration : 0;
      durationRef.current = length;
      setDuration(length);
      // A fresh source resets `playbackRate` to 1, so the listener's speed has to be re-applied per episode
      // rather than set once.
      audio.playbackRate = rateRef.current;
      if (pendingSeekRef.current > 0) {
        audio.currentTime = pendingSeekRef.current;
        pendingSeekRef.current = 0;
      }
      updatePositionState(audio);
    };
    const onTime = () => {
      const before = Math.floor(currentTimeRef.current);
      setPosition(audio.currentTime);
      const episode = currentRef.current;
      if (!episode) {
        return;
      }
      const seconds = Math.floor(audio.currentTime);
      if (seconds !== before) {
        // Once a second rather than on every tick: it is what brings the bar back after a reload, and a
        // second's precision is all a restored position shows.
        saveNowPlaying();
      }
      // A shared timestamp holds the stored position until playback has actually moved on from it, so
      // opening someone's link never rewrites where *they* were (§6.5).
      const floor = progressFloorRef.current;
      if (floor != null) {
        if (audio.currentTime < floor + PROGRESS_ADVANCE_SECONDS) {
          return;
        }
        progressFloorRef.current = null;
      }
      // Remembering the position is a disclosed feature with an off switch rather than a consent gate
      // (§12.5) — first-party, local, never profiled, written only after a deliberate press of play. When
      // it is off, nothing is written here or sent to the server.
      if (!progressEnabled()) {
        return;
      }
      // localStorage every tick is cheap and covers anonymous + logout; server writes are throttled. Guarded:
      // with storage blocked or full this threw several times a second inside a DOM event handler, where no
      // boundary reaches it (core#185). The server copy below still goes out for a signed-in listener.
      try {
        localStorage.setItem(progressKey(episode.id), String(seconds));
      } catch {
        // Not remembered on this device; playback is unaffected.
      }
      const now = Date.now();
      if (userRef.current && now - lastServerWriteRef.current > 10_000) {
        lastServerWriteRef.current = now;
        void api.put(`/api/me/progress/${episode.id}`, { positionSeconds: seconds }).catch(() => {});
      }
    };
    const onError = () => {
      // Only for an element that was actually pointed somewhere: `stop()` unloads by removing the source,
      // which is not a failure anyone needs telling about.
      const episode = currentRef.current;
      if (!episode || audio.getAttribute('src') == null) {
        return;
      }
      // A source the browser cannot load — a 404 on the media URL, a dead CDN, a codec it refuses — fires
      // `error` and nothing else. There was no listener for it, so the bar sat on an episode that silently
      // never started (core#170). The position is kept, so a retry resumes rather than restarts.
      if (currentTimeRef.current > 0) {
        pendingSeekRef.current = currentTimeRef.current;
      }
      setPlaying(false);
      setProblem('error');
      reportUnplayable(episode, audio.error?.code ?? 0);
    };
    const onEnded = () => {
      const episode = currentRef.current;
      if (episode) {
        try {
          localStorage.removeItem(progressKey(episode.id));
        } catch {
          // Nothing readable was stored either.
        }
        // Only when remembering is on. Writing a zero is still writing a row about what this person listened
        // to, on the say-so of a setting they turned off — and it was the one progress write with no gate.
        if (userRef.current && progressEnabled()) {
          void api.put(`/api/me/progress/${episode.id}`, { positionSeconds: 0 }).catch(() => {});
        }
        // Finished is "from the top next time", which is also what the element does on the next press — a
        // bar restored at the last second would play one second and end again.
        setPosition(0);
        saveNowPlaying();
      }
      void advance();
    };
    const onPositionChange = () => updatePositionState(audio);
    audio.playbackRate = rateRef.current;
    audio.volume = storedVolume();
    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('loadedmetadata', onLoaded);
    audio.addEventListener('timeupdate', onTime);
    audio.addEventListener('error', onError);
    audio.addEventListener('ended', onEnded);
    audio.addEventListener('seeked', onPositionChange);
    audio.addEventListener('ratechange', onPositionChange);
    return () => {
      audio.removeEventListener('play', onPlay);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('loadedmetadata', onLoaded);
      audio.removeEventListener('timeupdate', onTime);
      audio.removeEventListener('error', onError);
      audio.removeEventListener('ended', onEnded);
      audio.removeEventListener('seeked', onPositionChange);
      audio.removeEventListener('ratechange', onPositionChange);
    };
  }, [advance, saveNowPlaying, setPosition, setProblem]);

  // Media Session API: metadata + hardware/lock-screen controls.
  //
  // Keyed on the episode, not on the position. It had `currentTime` among its dependencies, so about four
  // times a second it built a new MediaMetadata and re-registered every handler — and with no cleanup, the
  // handlers outlived the episode and the provider, calling stale closures (core#170).
  useEffect(() => {
    if (!('mediaSession' in navigator)) {
      return;
    }
    const session = navigator.mediaSession;
    if (!current) {
      session.metadata = null;
      session.playbackState = 'none';
      return;
    }
    session.metadata = new MediaMetadata({
      title: current.title,
      // A podcast is its show: the lock screen's second line is where a listener looks for which one.
      artist: current.feedTitle ?? '',
      album: current.feedTitle ?? '',
      artwork: current.imageUrl ? [{ src: current.imageUrl }] : undefined,
    });
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ['play', () => audioRef.current?.paused && toggle()],
      ['pause', () => audioRef.current?.pause()],
      ['seekbackward', (details) => skip(-(details.seekOffset ?? 15))],
      ['seekforward', (details) => skip(details.seekOffset ?? 30)],
      ['seekto', (details) => details.seekTime != null && seek(details.seekTime)],
      ['nexttrack', () => void advance()],
    ];
    for (const [action, handler] of handlers) {
      try {
        session.setActionHandler(action, handler);
      } catch {
        // An action this browser does not support; the others still register.
      }
    }
    return () => {
      for (const [action] of handlers) {
        try {
          session.setActionHandler(action, null);
        } catch {
          // Same as above.
        }
      }
    };
  }, [current, toggle, skip, seek, advance]);

  const getCurrentTime = useCallback(() => currentTimeRef.current, []);

  // Two values, two identities. The state one is rebuilt on every `timeupdate` — it has to be, that is what
  // it carries. The actions one never changes, so a card's Play button, a deep link and every plugin mount
  // stop re-rendering at tick rate, which is what took one visitor's feed page from ~304 requests a second
  // to a handful (core#158).
  const state: PlayerStateValue = useMemo(
    () => ({ current, playing, currentTime, duration, volume, rate, problem }),
    [current, playing, currentTime, duration, volume, rate, problem],
  );
  const actions: PlayerActionsValue = useMemo(
    () => ({ play, toggle, seek, skip, setVolume, setRate, retry, stop, getCurrentTime }),
    [play, toggle, seek, skip, setVolume, setRate, retry, stop, getCurrentTime],
  );

  return (
    <PlayerActionsContext.Provider value={actions}>
      <PlayerStateContext.Provider value={state}>
        {children}
        {/* One audio element for the whole app; the bar shows once something is loaded. */}
        {/* No <track>: the source is a third-party podcast enclosure and there is no caption file to point
            at. Transcripts are a feed-level feature, not something the player can synthesise. */}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        <audio ref={audioRef} preload="metadata" />
        {current && <PlayerBar />}
      </PlayerStateContext.Provider>
    </PlayerActionsContext.Provider>
  );
}
