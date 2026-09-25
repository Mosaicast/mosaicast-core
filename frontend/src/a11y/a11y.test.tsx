// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import i18n from '../i18n';
import { composeTitle } from './documentTitle';
import { LiveRegion, announce } from './LiveRegion';
import { RouteFocus } from './RouteFocus';

describe('page language (core#171)', () => {
  it('follows the language the shell renders in', async () => {
    // Nothing wrote <html lang>: a screen reader read the German interface with an English voice.
    await act(() => i18n.changeLanguage('de'));
    expect(document.documentElement.lang).toBe('de');

    await act(() => i18n.changeLanguage('en'));
    expect(document.documentElement.lang).toBe('en');
  });

  it('names the language actually shown, not the one asked for that has no catalog', async () => {
    await act(() => i18n.changeLanguage('xx'));
    expect(document.documentElement.lang).toBe('en');
    await act(() => i18n.changeLanguage('en'));
  });
});

describe('document title (core#172)', () => {
  it('is the page, then the site', () => {
    expect(composeTitle('The Kraken', 'Deep Water')).toBe('The Kraken — Deep Water');
  });

  it('is the site alone for the site itself, and never the site twice', () => {
    expect(composeTitle(null, 'Deep Water')).toBe('Deep Water');
    expect(composeTitle('  ', 'Deep Water')).toBe('Deep Water');
    expect(composeTitle('Deep Water', 'Deep Water')).toBe('Deep Water');
  });
});

/** Two pages and a filter, the way the shell has them: a heading each, and a query that is not a new page. */
function Shell({ searchAutofocus = false }: { searchAutofocus?: boolean }) {
  const navigate = useNavigate();
  const [late, setLate] = useState(false);
  return (
    <>
      <nav>
        <button type="button" onClick={() => navigate('/second')}>go second</button>
        <button type="button" onClick={() => navigate('/?order=oldest')}>filter</button>
        <button type="button" onClick={() => navigate('/search')}>go search</button>
        <button type="button" onClick={() => navigate('/slow')}>go slow</button>
        <button type="button" onClick={() => setLate(true)}>load slow</button>
      </nav>
      <RouteFocus />
      <main id="main" tabIndex={-1}>
        <Routes>
          <Route path="/" element={<h1>First</h1>} />
          <Route path="/second" element={<h1>Second</h1>} />
          {/* eslint-disable-next-line jsx-a11y/no-autofocus */}
          <Route path="/search" element={<><h1>Search</h1><input aria-label="q" autoFocus={searchAutofocus} /></>} />
          <Route path="/slow" element={late ? <h1>Slow</h1> : <p>Loading…</p>} />
        </Routes>
      </main>
    </>
  );
}

function renderShell(props: { searchAutofocus?: boolean } = {}) {
  return render(
    <MemoryRouter>
      <Shell {...props} />
    </MemoryRouter>,
  );
}

const frame = () => act(() => new Promise((resolve) => requestAnimationFrame(() => resolve(undefined))));

describe('focus on navigation (core#172)', () => {
  it('moves to the new page heading, so a screen reader hears that the page changed', async () => {
    renderShell();
    const link = screen.getByRole('button', { name: 'go second' });
    link.focus();

    fireEvent.click(link);
    await frame();

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Second' }));
  });

  it('leaves focus alone when only the filters changed', async () => {
    renderShell();
    const filter = screen.getByRole('button', { name: 'filter' });
    filter.focus();

    fireEvent.click(filter);
    await frame();

    expect(document.activeElement).toBe(filter);
  });

  it('does not overrule a page that placed focus itself', async () => {
    renderShell({ searchAutofocus: true });

    fireEvent.click(screen.getByRole('button', { name: 'go search' }));
    await frame();

    expect(document.activeElement).toBe(screen.getByLabelText('q'));
  });

  it('waits for the heading of a page that is still loading', async () => {
    renderShell();

    fireEvent.click(screen.getByRole('button', { name: 'go slow' }));
    await frame();
    fireEvent.click(screen.getByRole('button', { name: 'load slow' }));
    await frame();

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Slow' }));
  });
});

describe('live region (core#172)', () => {
  it('exists before anything is said, and says it', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      render(<LiveRegion />);
      const region = screen.getByRole('status');
      expect(region).toHaveAttribute('aria-live', 'polite');
      expect(region).toBeEmptyDOMElement();

      act(() => announce('Saved'));
      await frame();

      expect(region).toHaveTextContent('Saved');
      // Emptied afterwards, so someone browsing the page later does not find a stale sentence.
      act(() => {
        vi.advanceTimersByTime(7000);
      });
      expect(region).toBeEmptyDOMElement();
    } finally {
      vi.useRealTimers();
    }
  });
});
