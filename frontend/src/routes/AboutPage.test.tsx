// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import type { PublicPlugin } from '../plugins/types';
import { AboutPage } from './AboutPage';

/**
 * The About page is the one surface a *bare* install still has to answer something on, so absence is the
 * interesting case throughout: no plugins, no admin blurb, no legal pages. Each section has to disappear
 * on its own without taking the page with it.
 */

vi.mock('../plugins/PluginRegistry', () => ({
  usePluginRegistry: () => ({ plugins: mockPlugins }),
}));

vi.mock('../api/MetaContext', () => ({
  useMeta: () => ({ name: 'Mosaicast', version: '0.6.15', devLoginEnabled: false }),
}));

let mockPlugins: PublicPlugin[] = [];

/** `GET /api/legal/about` — either the operator's blurb, or the 404 of one they deleted. */
function mockAbout(page: { title: string; html: string } | null) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        page
          ? {
              ok: true,
              status: 200,
              text: () =>
                Promise.resolve(JSON.stringify({ slug: 'about', role: 'about', ...page })),
            }
          : { ok: false, status: 404, text: () => Promise.resolve('') },
      ),
    ),
  );
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AboutPage />
    </MemoryRouter>,
  );
}

describe('AboutPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    mockPlugins = [];
  });

  it('answers "what is this site?" on a bare install', async () => {
    mockAbout(null);
    renderPage();

    // The fixed text and the source link are shipped, so they survive an install with nothing configured.
    expect(await screen.findByRole('heading', { name: 'What this is' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Read or fork the source' })).toHaveAttribute(
      'href',
      'https://github.com/Mosaicast/mosaicast-core',
    );
    expect(screen.getByText('0.6.15')).toBeInTheDocument();

    // No plugins is a sentence, not an empty table.
    expect(await screen.findByText('This site runs no plugins.')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Plugin' })).toBeNull();

    // And the credits are generated, so they are there regardless of configuration.
    expect(screen.getByRole('link', { name: /Bootstrap Icons/ })).toBeInTheDocument();
  });

  it('leads with the operator blurb, not with the platform', async () => {
    mockAbout({ title: 'About this instance', html: '<p>Two people and a microphone.</p>' });
    renderPage();

    expect(await screen.findByRole('heading', { name: 'About this instance' })).toBeInTheDocument();
    expect(screen.getByText('Two people and a microphone.')).toBeInTheDocument();

    // Order is the point, not decoration: someone arrived at this podcast's site, not at a piece of
    // software, so "whose site is this?" is answered before "what is it running on?".
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual([
      'About this instance',
      'What this is',
      'Plugins on this site',
      'Built with',
    ]);
  });

  it('opens on the platform when the operator deleted their blurb', async () => {
    mockAbout(null);
    renderPage();

    const headings = (await screen.findAllByRole('heading', { level: 2 })).map((h) => h.textContent);
    expect(headings).toEqual(['What this is', 'Plugins on this site', 'Built with']);
  });

  it('lists installed plugins with whatever credit they declared', async () => {
    mockPlugins = [
      {
        id: 'wiki',
        name: 'Wiki',
        version: '0.1.0',
        frontend: null,
        slots: [],
        license: 'AGPL-3.0-or-later',
        author: 'The Mosaicast Authors',
        homepage: 'https://example.test/wiki',
      },
      // Declares nothing at all — the older-plugin case, which must render rather than break the table.
      { id: 'bare', name: 'Bare', version: '9.9.9', frontend: null, slots: [] },
    ];
    mockAbout(null);
    renderPage();

    expect(await screen.findByRole('link', { name: 'Wiki' })).toHaveAttribute(
      'href',
      'https://example.test/wiki',
    );
    expect(screen.getByText('AGPL-3.0-or-later')).toBeInTheDocument();
    expect(screen.getByText('The Mosaicast Authors')).toBeInTheDocument();

    // A plugin with no homepage is plain text, not a dead link.
    expect(screen.getByRole('rowheader', { name: 'Bare' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Bare' })).toBeNull();
  });

  it('credits both what ships and what only builds', async () => {
    mockAbout(null);
    renderPage();

    // Two scopes, because crediting only what ships would quietly drop the test and build tooling.
    expect(await screen.findByText('Ships in the site you are using')).toBeInTheDocument();
    expect(screen.getByText('Used to build and test it')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Spring Boot/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Vitest/ })).toBeInTheDocument();
  });
});
