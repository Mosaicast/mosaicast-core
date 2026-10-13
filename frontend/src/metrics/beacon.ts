// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/** One counted thing (ARCHITECTURE §19): no identifier, no URL, no referrer — only these three. */
export interface CountEvent {
  kind: 'view' | 'play' | 'progress';
  subject: string;
  milestone?: 25 | 50 | 75 | 100;
}

const MAX_BATCH = 10; // the endpoint's own cap
const DELAY_MS = 2000;

let queue: CountEvent[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;

/**
 * Sends the queued events. `fetch` with `credentials: 'omit'` rather than `navigator.sendBeacon`, which
 * always carries the site's cookies: the counting request must not be able to tell who sent it, not even a
 * signed-in visitor. `keepalive` lets it survive the page being closed.
 */
export function flush(): void {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  while (queue.length > 0) {
    const batch = queue.slice(0, MAX_BATCH);
    queue = queue.slice(MAX_BATCH);
    void fetch('/api/metrics/events', {
      method: 'POST',
      credentials: 'omit',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ events: batch }),
    }).catch(() => {
      /* counting is best effort; a lost batch is a lost count, nothing more */
    });
  }
}

/** Queues an event; a short delay lets a page view and a play share one request. */
export function count(event: CountEvent): void {
  queue.push(event);
  if (queue.length >= MAX_BATCH) {
    flush();
  } else if (!timer) {
    timer = setTimeout(flush, DELAY_MS);
  }
}

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flush();
  });
}

/** For tests: drops anything queued without sending. */
export function resetForTests(): void {
  queue = [];
  if (timer) clearTimeout(timer);
  timer = null;
}
