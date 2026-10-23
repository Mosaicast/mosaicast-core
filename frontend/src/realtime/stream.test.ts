// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { ChannelEvent } from '@mosaicast/plugin-sdk';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { onAccessChanged, resetForTests, subscribe } from './stream';

/** Stands in for the browser's EventSource: records what was opened and lets a test push frames. */
class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.OPEN;
  closed = false;
  private listeners = new Map<string, ((e: MessageEvent | Event) => void)[]>();

  constructor(
    readonly url: string,
    readonly init?: EventSourceInit,
  ) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, listener: (e: MessageEvent | Event) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
  }

  emit(name: string, data: unknown) {
    const event = new MessageEvent(name, { data: JSON.stringify(data) });
    this.listeners.get(name)?.forEach((listener) => listener(event));
  }

  fail(readyState: number) {
    this.readyState = readyState;
    this.listeners.get('error')?.forEach((listener) => listener(new Event('error')));
  }

  close() {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }
}

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };

let posts: { url: string; body: unknown }[];
let deletes: string[];
let replies: Reply[];
let nextSub: number;

beforeEach(() => {
  FakeEventSource.instances = [];
  posts = [];
  deletes = [];
  replies = [];
  nextSub = 1;
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') {
        deletes.push(url);
        return Promise.resolve(response({ status: 204 }));
      }
      posts.push({ url, body: JSON.parse(init?.body as string) });
      return Promise.resolve(response(replies.shift() ?? { status: 200, body: { sub: `s${nextSub++}` } }));
    }),
  );
});

afterEach(() => {
  resetForTests();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function response({ status, body, headers = {} }: Reply) {
  const text = body === undefined ? '' : JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: new Headers(headers),
    text: () => Promise.resolve(text),
    json: () => (text ? Promise.resolve(JSON.parse(text)) : Promise.reject(new Error('no body'))),
  };
}

function open(name: string, extra: { onReset?: () => void } = {}) {
  const events: ChannelEvent[] = [];
  const statuses: string[] = [];
  const stop = subscribe({
    pluginId: 'bingo',
    scope: { type: 'episode', id: 'e1' },
    name,
    onEvent: (e) => events.push(e),
    onStatus: (s) => statuses.push(s),
    ...extra,
  });
  return { events, statuses, stop };
}

const es = () => FakeEventSource.instances[FakeEventSource.instances.length - 1];
const flush = () => vi.waitFor(() => Promise.resolve());

describe('the tab stream', () => {
  it('opens nothing until something subscribes, then one stream for everyone', () => {
    expect(FakeEventSource.instances).toHaveLength(0);
    open('a');
    open('b');
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(es().url).toBe('/api/realtime/stream');
  });

  it('subscribes on hello and routes events by subscription', async () => {
    const a = open('a');
    const b = open('b');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses).toEqual(['connecting', 'open']));
    await vi.waitFor(() => expect(b.statuses).toEqual(['connecting', 'open']));
    expect(posts[0].body).toEqual({ stream: 'st1', plugin: 'bingo', scope: { type: 'episode', id: 'e1' }, name: 'a' });

    es().emit('msg', { sub: 's2', event: 'moved', data: { square: 3 }, at: '2026-10-23T10:00:00Z' });
    expect(b.events).toEqual([{ event: 'moved', data: { square: 3 }, at: '2026-10-23T10:00:00Z' }]);
    expect(a.events).toEqual([]);
  });

  it('starts over on a stream that did not resume, and tells the plugin to refetch', async () => {
    const onReset = vi.fn();
    const a = open('a', { onReset });
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses).toContain('open'));
    expect(onReset).not.toHaveBeenCalled();

    es().fail(FakeEventSource.CONNECTING);
    expect(a.statuses.at(-1)).toBe('connecting');
    es().emit('hello', { stream: 'st2', resumed: false });
    await vi.waitFor(() => expect(onReset).toHaveBeenCalledTimes(1));
    expect(a.statuses.at(-1)).toBe('open');
    expect(posts.at(-1)?.body).toMatchObject({ stream: 'st2', name: 'a' });
  });

  it('keeps a resumed stream without resubscribing', async () => {
    const a = open('a');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses).toContain('open'));
    es().fail(FakeEventSource.CONNECTING);
    es().emit('hello', { stream: 'st1', resumed: true });
    expect(a.statuses.at(-1)).toBe('open');
    expect(posts).toHaveLength(1);
  });

  it('ends a refused subscription for good', async () => {
    const a = open('a');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses).toContain('open'));
    es().emit('refused', { sub: 's1' });
    expect(a.statuses.at(-1)).toBe('refused');
    es().emit('msg', { sub: 's1', event: 'late', data: 1, at: 'x' });
    expect(a.events).toEqual([]);
  });

  it('a plugin the authorizer refuses is refused, not retried', async () => {
    replies.push({ status: 403, body: { type: 'https://mosaicast.dev/problems/forbidden' } });
    const a = open('a');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses.at(-1)).toBe('refused'));
    expect(posts).toHaveLength(1);
  });

  it('a rate-limited subscribe stays connecting and retries when told', async () => {
    vi.useFakeTimers();
    replies.push({ status: 429, body: { type: 'https://mosaicast.dev/problems/too-many-requests', retryAfterMs: 500 } });
    const a = open('a');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    await flush();
    expect(a.statuses).toEqual(['connecting']);
    await vi.advanceTimersByTimeAsync(600);
    await vi.waitFor(() => expect(a.statuses).toEqual(['connecting', 'open']));
  });

  it('closes everything when access changes, and says so', async () => {
    const changed = vi.fn();
    onAccessChanged(changed);
    const a = open('a');
    const b = open('b');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(b.statuses).toContain('open'));
    es().emit('closed', { subs: ['s1'], reason: 'access' });
    expect(a.statuses.at(-1)).toBe('closed');
    expect(b.statuses.at(-1)).toBe('closed');
    expect(es().closed).toBe(true);
    expect(changed).toHaveBeenCalledTimes(1);
  });

  it('closes the stream a moment after the last subscription ends', async () => {
    vi.useFakeTimers();
    const a = open('a');
    es().emit('hello', { stream: 'st1', resumed: false });
    await vi.waitFor(() => expect(a.statuses).toContain('open'));
    a.stop();
    expect(a.statuses.at(-1)).toBe('closed');
    await vi.waitFor(() => expect(deletes).toEqual(['/api/realtime/subscriptions/s1']));
    // A re-render's unsubscribe/subscribe inside the window reuses the stream.
    open('b');
    await vi.advanceTimersByTimeAsync(3000);
    expect(es().closed).toBe(false);
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('reopens with backoff when the browser gives up on the stream', async () => {
    vi.useFakeTimers();
    open('a');
    es().fail(FakeEventSource.CLOSED);
    expect(FakeEventSource.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1100);
    expect(FakeEventSource.instances).toHaveLength(2);
  });
});
