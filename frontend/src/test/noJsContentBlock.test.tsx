// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';
import { createRoot } from 'react-dom/client';
import { act } from 'react';

/**
 * The server injects a readable no-JS content block *inside* `#root` (ARCHITECTURE §6.6,
 * `IndexHtmlService.injectContent`), relying on React replacing the container's contents when it mounts.
 *
 * That reliance is the whole design — there is no clean-up code in `main.tsx` — so it is pinned here rather
 * than assumed. If a future React version stopped clearing the container, every page would render its
 * server-side content block *and* the app underneath it, and nothing else in the suite would notice.
 */
describe('the server-rendered no-JS content block', () => {
  it('is replaced by the app when React mounts into the same container', () => {
    // Testing Library sets this for its own `render`; this test drives `createRoot` directly, the way
    // `main.tsx` does, so it has to opt in itself.
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

    const container = document.createElement('div');
    container.id = 'root';
    container.innerHTML = '<h1>The Lighthouse Episode</h1><p>Crawler-readable show notes.</p>';
    document.body.appendChild(container);

    expect(container.textContent).toContain('Crawler-readable show notes.');

    act(() => {
      createRoot(container).render(<main>The app</main>);
    });

    expect(container.textContent).not.toContain('Crawler-readable show notes.');
    expect(container.textContent).toContain('The app');

    document.body.removeChild(container);
  });
});
