// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { buildCtx } from '../plugins/buildCtx';
import { PluginRegistryProvider } from '../plugins/PluginRegistry';
import { PluginPage } from './PluginPage';

const PLUGIN = {
  id: 'good',
  name: 'Good',
  version: '1.0.0',
  frontend: { entry: 'fixture.js', elements: ['fixture-page'] },
  slots: [{ scope: 'site', element: 'fixture-page', placement: 'page', visibleTo: 'anonymous', order: null }],
};

function stubFetch(plugins: unknown[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve({
        ok: true,
        status: 200,
        text: () => Promise.resolve(JSON.stringify(url.includes('manifest') ? plugins : [])),
      }),
    ),
  );
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <PluginRegistryProvider>
        <Routes>
          <Route path="/p/:pluginId/*" element={<PluginPage />} />
        </Routes>
      </PluginRegistryProvider>
    </MemoryRouter>,
  );
}

describe('Plugin deep links (M5 E5c)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('renders the page region for a plugin that declares a page slot', async () => {
    stubFetch([PLUGIN]);
    const { container } = renderAt('/p/good/some/page');
    await waitFor(() => {
      expect(container.querySelector('[data-slot="page"]')).not.toBeNull();
    });
  });

  it('404s for a plugin that declares no page slot', async () => {
    stubFetch([{ ...PLUGIN, slots: [{ ...PLUGIN.slots[0], placement: 'sidebar' }] }]);
    renderAt('/p/good/some/page');
    expect(await screen.findByText('Not found')).toBeInTheDocument();
  });

  it('404s for an unknown plugin', async () => {
    stubFetch([]);
    renderAt('/p/nope/x');
    expect(await screen.findByText('Not found')).toBeInTheDocument();
  });

  it('is not a 404 while the registry has not answered yet (core#185)', async () => {
    // The manifest request held open: a deep link used to render "Not found" for exactly this long.
    let answer: (value: unknown) => void = () => {};
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise((resolve) => {
        answer = resolve;
      })),
    );
    renderAt('/p/good/some/page');

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('Not found')).not.toBeInTheDocument();

    answer({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify([PLUGIN])) });
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument());
    expect(screen.queryByText('Not found')).not.toBeInTheDocument();
  });

  it('says the registry failed, and offers to ask again, instead of claiming the page does not exist', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValue({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify([PLUGIN])) });
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = renderAt('/p/good/some/page');

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.queryByText('Not found')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(container.querySelector('[data-slot="page"]')).not.toBeNull());
  });

  it('hands the subpath below /p/{id}/ to the plugin as ctx.route', () => {
    const ctx = buildCtx({
      pluginId: 'good',
      scope: { type: 'site', id: 'main' },
      episodes: [],
      episodeLabels: {},
      user: null,
      theme: undefined,
      locale: 'en',
      uiLocales: [{ code: 'en', nativeName: 'English', isDefault: true }],
      contentLocales: [{ code: 'en', nativeName: 'English', isDefault: true }],
      playerCurrentTime: () => 0,
      playerSeekTo: () => {},
      routePath: 'some/page',
    });
    expect(ctx.route.path).toBe('some/page');
  });
});
