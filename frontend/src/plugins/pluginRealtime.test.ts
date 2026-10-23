// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { isActionRejected, isRateLimited, PROBLEM_TYPES } from '@mosaicast/plugin-sdk';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { makePluginActions, makePluginChannels } from './pluginRealtime';

const SCOPE = { type: 'episode', id: 'the-cast-s01e01' } as const;

interface Sent {
  url: string;
  method: string | undefined;
  body: unknown;
}

function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  const sent: Sent[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      sent.push({ url, method: init?.method, body: init?.body ? JSON.parse(init.body as string) : undefined });
      const text = body === undefined ? '' : JSON.stringify(body);
      return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        statusText: '',
        headers: new Headers(headers),
        text: () => Promise.resolve(text),
        json: () => (text ? Promise.resolve(JSON.parse(text)) : Promise.reject(new Error('no body'))),
      });
    }),
  );
  return sent;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ctx.actions', () => {
  it('posts the scope and payload, defaulting the scope to the mount', async () => {
    const sent = respond(200, { ok: true });
    const answer = await makePluginActions('bingo', true, SCOPE).call('move', { square: 12 });
    expect(answer).toEqual({ ok: true });
    expect(sent[0]).toEqual({
      url: '/api/plugins/bingo/actions/move',
      method: 'POST',
      body: { scope: SCOPE, payload: { square: 12 } },
    });
  });

  it('sends an explicit scope and a null payload when there is none', async () => {
    const sent = respond(204);
    const answer = await makePluginActions('bingo', true, SCOPE).call('ping', undefined, {
      scope: { type: 'site', id: 'main' },
    });
    expect(answer).toBeNull();
    expect(sent[0].body).toEqual({ scope: { type: 'site', id: 'main' }, payload: null });
  });

  it('refuses a signed-out visitor without a request', async () => {
    const sent = respond(200, {});
    const call = makePluginActions('bingo', false, SCOPE).call('move', 1);
    await expect(call).rejects.toMatchObject({ status: 401, problem: { type: PROBLEM_TYPES.unauthorized } });
    expect(sent).toHaveLength(0);
  });

  it('surfaces the plugin rejection code for isActionRejected', async () => {
    respond(422, { type: PROBLEM_TYPES.actionRejected, code: 'not-your-turn', detail: 'Wait.' });
    const error = await makePluginActions('bingo', true, SCOPE).call('move', 1).catch((e: unknown) => e);
    expect(isActionRejected(error)).toBe(true);
  });

  it('fills retryAfterMs from Retry-After for isRateLimited', async () => {
    respond(429, { type: PROBLEM_TYPES.tooManyRequests }, { 'Retry-After': '3' });
    const error = await makePluginActions('bingo', true, SCOPE).call('move', 1).catch((e: unknown) => e);
    expect(isRateLimited(error)).toBe(true);
    expect((error as { problem: { retryAfterMs: number } }).problem.retryAfterMs).toBe(3000);
  });
});

describe('ctx.channels', () => {
  it('refuses a signed-out visitor without opening anything', () => {
    const opened = vi.fn();
    vi.stubGlobal('EventSource', opened);
    const statuses: string[] = [];
    const sub = makePluginChannels('bingo', false, SCOPE).subscribe('game', () => {}, {
      onStatus: (s) => statuses.push(s),
    });
    expect(statuses).toEqual(['refused']);
    expect(opened).not.toHaveBeenCalled();
    sub.close();
  });
});
