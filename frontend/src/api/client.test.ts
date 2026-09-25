// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, REQUEST_TIMEOUT_MS, api, onUnauthorized } from './client';

/**
 * The request budget (core#185): there was no timeout, no cancellation and no single place that heard about
 * an expired session. A `fetch` that honours its signal stands in for the network.
 */

/** A fetch that never answers on its own, but rejects the way the platform does when its signal aborts. */
function hangingFetch() {
  return vi.fn(
    (_url: string, init: RequestInit) =>
      new Promise((_, reject) => {
        init.signal?.addEventListener('abort', () =>
          reject(new DOMException('The operation was aborted.', 'AbortError')),
        );
      }),
  );
}

function answering(status: number, body = '') {
  return vi.fn(() =>
    Promise.resolve({
      ok: status >= 200 && status < 300,
      status,
      statusText: '',
      json: () => Promise.reject(new Error('no body')),
      text: () => Promise.resolve(body),
    }),
  );
}

describe('api client', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('gives up on a request the server never answers, instead of loading forever', async () => {
    vi.stubGlobal('fetch', hangingFetch());

    const pending = api.get('/api/episodes/slow');
    const outcome = expect(pending).rejects.toMatchObject({ name: 'ApiError', status: 0 });
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);

    await outcome;
  });

  it('lets the caller cancel, and says so as an abort rather than a failure', async () => {
    vi.stubGlobal('fetch', hangingFetch());
    const controller = new AbortController();

    const pending = api.get('/api/episodes/slow', { signal: controller.signal });
    controller.abort();

    const error = await pending.catch((cause: unknown) => cause);
    expect(error).not.toBeInstanceOf(ApiError);
    expect((error as DOMException).name).toBe('AbortError');
  });

  it('gives an upload as long as it takes', async () => {
    const fetchMock = hangingFetch();
    vi.stubGlobal('fetch', fetchMock);

    let settled = false;
    void api.upload('/api/admin/branding/logo', new FormData()).finally(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS * 4);

    expect(settled).toBe(false);
  });

  it('tells one place when a request comes back 401, so an expired session is noticed once', async () => {
    vi.stubGlobal('fetch', answering(401));
    const heard = vi.fn();
    const unsubscribe = onUnauthorized(heard);
    try {
      await expect(api.get('/api/me/notifications')).rejects.toMatchObject({ status: 401 });
      expect(heard).toHaveBeenCalledTimes(1);

      // `/api/me` is how the shell asks whether anyone is signed in; its 401 is an answer, not news — and
      // treating it as news would loop, since the listener's response is to ask `/api/me`.
      await expect(api.get('/api/me')).rejects.toMatchObject({ status: 401 });
      expect(heard).toHaveBeenCalledTimes(1);
    } finally {
      unsubscribe();
    }
  });

  it('still reads an empty success as nothing and a JSON one as data', async () => {
    vi.stubGlobal('fetch', answering(200, '{"a":1}'));
    await expect(api.get('/api/x')).resolves.toEqual({ a: 1 });

    vi.stubGlobal('fetch', answering(204));
    await expect(api.del('/api/x')).resolves.toBeUndefined();
  });
});
