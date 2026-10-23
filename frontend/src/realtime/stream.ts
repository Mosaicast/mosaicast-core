// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { ChannelEvent, ChannelStatus, Scope } from '@mosaicast/plugin-sdk';

import { api, ApiError } from '../api/client';

/**
 * The tab's one live connection for plugin channels (platformApi 0.20.0, ARCHITECTURE §7.9).
 *
 * Every plugin's subscriptions share one Server-Sent Events stream, opened when the first one starts and
 * closed a moment after the last one ends — the moment absorbs a plugin re-subscribing when its `ctx` is
 * reassigned, which would otherwise drop and reopen the connection on every re-render. One stream rather than
 * one per plugin because a browser on plain HTTP/1.1 allows six connections per site across all tabs.
 *
 * The wire is the shell's business and nothing a plugin sees names it (`ctx.channels` is transport-neutral),
 * so this module is the only place that knows it: `hello` names the stream and says whether a reconnect
 * resumed it; `msg` carries an event for one subscription; `refused` and `closed` end subscriptions for good.
 * A reconnect that did not resume means every subscription is gone on the server — each is made again and
 * its plugin told to refetch (`onReset`), the same path as a first load.
 */

const STREAM_PATH = '/api/realtime/stream';
const SUBSCRIPTIONS_PATH = '/api/realtime/subscriptions';
/** How long the stream stays open with nothing subscribed, so a re-render's unsubscribe/subscribe is free. */
const IDLE_CLOSE_MS = 2_000;
const BACKOFF_MIN_MS = 1_000;
const BACKOFF_MAX_MS = 30_000;

export interface StreamSubscription {
  pluginId: string;
  scope: Scope;
  name: string;
  onEvent: (event: ChannelEvent) => void;
  onReset?: () => void;
  onStatus?: (status: ChannelStatus) => void;
}

interface Entry extends StreamSubscription {
  status: ChannelStatus;
  /** The server's id for it on the current stream; unset until admitted, and again after a lost stream. */
  sub?: string;
  /** Whether it has ever been open — a re-admission after that owes the plugin an `onReset`. */
  opened: boolean;
  /** Bumped whenever the entry must be (re)registered, so a stale answer is recognised and ignored. */
  attempt: number;
  retry?: ReturnType<typeof setTimeout>;
  failures: number;
}

let source: EventSource | null = null;
let streamId: string | null = null;
const entries = new Set<Entry>();
const bySub = new Map<string, Entry>();
let idleTimer: ReturnType<typeof setTimeout> | undefined;
let reopenTimer: ReturnType<typeof setTimeout> | undefined;
let reopenFailures = 0;
const accessListeners = new Set<() => void>();

/**
 * Frames for a subscription id this tab has not heard back about yet.
 *
 * The server admits a subscription, and may publish to it — from the plugin's own `onJoin`, typically — before
 * the subscribe request has returned its id here. Without this, those first events were dropped as "for nobody",
 * which is exactly when a game sends its presence list. They are held briefly and replayed in order once the id
 * is known; anything still unclaimed after {@link EARLY_TTL_MS} belonged to a subscription this tab gave up on.
 */
type EarlyFrame = { type: 'msg' | 'refused' | 'closed'; data: unknown; at: number };
const early = new Map<string, EarlyFrame[]>();
const EARLY_TTL_MS = 10_000;
const EARLY_PER_SUB = 64;

/**
 * Subscribes to "the host ended live updates because access changed" — a sign-out elsewhere, a role change,
 * the site closing. The user context listens and re-reads who is signed in, which hands every plugin a new
 * `ctx` with the right role.
 */
export function onAccessChanged(listener: () => void): () => void {
  accessListeners.add(listener);
  return () => {
    accessListeners.delete(listener);
  };
}

/** Starts a subscription; the returned function ends it (idempotent). */
export function subscribe(request: StreamSubscription): () => void {
  const entry: Entry = { ...request, status: 'connecting', opened: false, attempt: 0, failures: 0 };
  entries.add(entry);
  request.onStatus?.('connecting');
  ensureOpen();
  if (streamId) {
    void register(entry);
  }
  return () => end(entry, 'closed', true);
}

function ensureOpen() {
  clearTimeout(idleTimer);
  idleTimer = undefined;
  if (source || reopenTimer) {
    return;
  }
  const es = new EventSource(STREAM_PATH, { withCredentials: true });
  source = es;
  es.addEventListener('hello', (e) => onHello(es, e as MessageEvent));
  es.addEventListener('msg', (e) => onMessage(e as MessageEvent));
  es.addEventListener('refused', (e) => onRefused(e as MessageEvent));
  es.addEventListener('closed', (e) => onClosed(e as MessageEvent));
  es.addEventListener('error', () => onError(es));
}

function onHello(es: EventSource, e: MessageEvent) {
  if (es !== source) {
    return;
  }
  reopenFailures = 0;
  const hello = parse<{ stream: string; resumed: boolean }>(e.data);
  if (!hello) {
    return;
  }
  if (hello.resumed && hello.stream === streamId) {
    // Picked up where it left off; the server replays what was missed right after this.
    entries.forEach((entry) => entry.sub && setStatus(entry, 'open'));
    return;
  }
  // A new stream: nothing we had survives on the server.
  streamId = hello.stream;
  bySub.clear();
  entries.forEach((entry) => {
    entry.sub = undefined;
    void register(entry);
  });
}

function onMessage(e: MessageEvent) {
  const frame = parse<{ sub: string; event: string; data: unknown; at: string }>(e.data);
  if (frame && !bySub.has(frame.sub)) {
    hold(frame.sub, 'msg', frame);
    return;
  }
  deliver(frame);
}

function deliver(frame: { sub: string; event: string; data: unknown; at: string } | null) {
  const entry = frame ? bySub.get(frame.sub) : undefined;
  if (frame && entry) {
    try {
      entry.onEvent({ event: frame.event, data: frame.data ?? null, at: frame.at });
    } catch (err) {
      // A plugin's handler throwing must not stop the stream for every other plugin on the page.
      console.error(`Plugin ${entry.pluginId}: channel handler failed`, err);
    }
  }
}

function onRefused(e: MessageEvent) {
  const frame = parse<{ sub: string }>(e.data);
  if (frame && !bySub.has(frame.sub)) {
    hold(frame.sub, 'refused', frame);
    return;
  }
  const entry = frame ? bySub.get(frame.sub) : undefined;
  if (entry) {
    end(entry, 'refused', false);
  }
}

function onClosed(e: MessageEvent) {
  const frame = parse<{ subs: string[]; reason?: string }>(e.data);
  if (!frame) {
    return;
  }
  for (const sub of frame.subs ?? []) {
    const entry = bySub.get(sub);
    if (entry) {
      end(entry, 'closed', false);
    } else if (frame.reason !== 'access') {
      hold(sub, 'closed', null);
    }
  }
  if (frame.reason === 'access') {
    // The server ends the whole stream after this; do not let the browser reconnect it.
    shutDown();
    accessListeners.forEach((listener) => listener());
  }
}

function onError(es: EventSource) {
  if (es !== source) {
    return;
  }
  entries.forEach((entry) => entry.status === 'open' && setStatus(entry, 'connecting'));
  if (es.readyState === EventSource.CLOSED) {
    // Refused outright (signed out, over a cap) — the browser will not retry this one, so we do.
    source = null;
    streamId = null;
    if (entries.size > 0) {
      const wait = backoff(reopenFailures++);
      reopenTimer = setTimeout(() => {
        reopenTimer = undefined;
        if (entries.size > 0) {
          ensureOpen();
        }
      }, wait);
    }
  }
  // Otherwise the browser reconnects by itself, sending the last event id, and `hello` says how it went.
}

async function register(entry: Entry) {
  const stream = streamId;
  if (!stream || !entries.has(entry)) {
    return;
  }
  clearTimeout(entry.retry);
  const attempt = ++entry.attempt;
  try {
    const answer = await api.post<{ sub: string }>(SUBSCRIPTIONS_PATH, {
      stream,
      plugin: entry.pluginId,
      scope: { type: entry.scope.type, id: entry.scope.id },
      name: entry.name,
    });
    if (!entries.has(entry) || attempt !== entry.attempt || stream !== streamId) {
      // Ended, or superseded by a newer stream, while this was in flight: give the server's one back.
      void api.del(`${SUBSCRIPTIONS_PATH}/${encodeURIComponent(answer.sub)}`).catch(() => {});
      return;
    }
    entry.sub = answer.sub;
    entry.failures = 0;
    bySub.set(answer.sub, entry);
    setStatus(entry, 'open');
    if (entry.opened) {
      entry.onReset?.();
    }
    entry.opened = true;
    replayEarly(answer.sub);
  } catch (err) {
    if (!entries.has(entry) || attempt !== entry.attempt) {
      return;
    }
    const status = err instanceof ApiError ? err.status : 0;
    if (status === 409) {
      // The stream is gone on the server; the next `hello` registers this again.
      return;
    }
    if (status === 0 || status === 429 || status >= 500) {
      // Not now, not never: try again when the server says, or back off.
      const hinted = err instanceof ApiError ? err.problem?.retryAfterMs : undefined;
      entry.retry = setTimeout(() => void register(entry), hinted ?? backoff(entry.failures++));
      return;
    }
    end(entry, 'refused', false);
  }
}

/** Ends one subscription; `byClient` means the plugin asked, so the server is told too. */
function end(entry: Entry, status: 'closed' | 'refused', byClient: boolean) {
  if (!entries.delete(entry)) {
    return;
  }
  clearTimeout(entry.retry);
  entry.attempt++;
  if (entry.sub) {
    bySub.delete(entry.sub);
    if (byClient) {
      void api.del(`${SUBSCRIPTIONS_PATH}/${encodeURIComponent(entry.sub)}`).catch(() => {});
    }
  }
  setStatus(entry, status);
  if (entries.size === 0 && !idleTimer) {
    idleTimer = setTimeout(shutDown, IDLE_CLOSE_MS);
  }
}

/** Keeps a frame for a subscription id not known yet; bounded per id and in time. */
function hold(sub: string, type: EarlyFrame['type'], data: unknown) {
  const now = Date.now();
  for (const [id, frames] of early) {
    if (frames.length > 0 && now - frames[0].at > EARLY_TTL_MS) {
      early.delete(id);
    }
  }
  const frames = early.get(sub) ?? [];
  if (frames.length < EARLY_PER_SUB) {
    frames.push({ type, data, at: now });
    early.set(sub, frames);
  }
}

/** Replays what arrived for a subscription before its id did, in arrival order. */
function replayEarly(sub: string) {
  const frames = early.get(sub);
  early.delete(sub);
  for (const frame of frames ?? []) {
    const entry = bySub.get(sub);
    if (!entry) {
      return;
    }
    if (frame.type === 'msg') {
      deliver(frame.data as { sub: string; event: string; data: unknown; at: string });
    } else {
      end(entry, frame.type, false);
    }
  }
}

function shutDown() {
  // Ended by the server (access changed) with subscriptions still listed: they are over too.
  [...entries].forEach((entry) => end(entry, 'closed', false));
  clearTimeout(idleTimer);
  clearTimeout(reopenTimer);
  idleTimer = undefined;
  reopenTimer = undefined;
  source?.close();
  source = null;
  streamId = null;
  bySub.clear();
  early.clear();
}

function setStatus(entry: Entry, status: ChannelStatus) {
  if (entry.status === status) {
    return;
  }
  entry.status = status;
  try {
    entry.onStatus?.(status);
  } catch (err) {
    console.error(`Plugin ${entry.pluginId}: channel status handler failed`, err);
  }
}

/** Exponential, capped, with jitter so a restart does not bring every tab back in the same instant. */
function backoff(failures: number): number {
  const ceiling = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * 2 ** failures);
  return ceiling / 2 + Math.random() * (ceiling / 2);
}

function parse<T>(data: unknown): T | null {
  try {
    return typeof data === 'string' ? (JSON.parse(data) as T) : null;
  } catch {
    return null;
  }
}

/** Forgets everything; for tests only. */
export function resetForTests() {
  shutDown();
  entries.clear();
  reopenFailures = 0;
  accessListeners.clear();
}
