// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { useRoutedLinks } from './useRoutedLinks';

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname}</output>;
}

/** Injected markup, the way show notes arrive: plain anchors, not router links. */
function Notes({ html }: { html: string }) {
  const ref = useRoutedLinks<HTMLDivElement>();
  return <div ref={ref} dangerouslySetInnerHTML={{ __html: html }} />;
}

function renderNotes(html: string) {
  return render(
    <MemoryRouter initialEntries={['/episodes/here']}>
      <Routes>
        <Route path="*" element={<><Notes html={html} /><Where /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Clicks, and answers whether the browser would still have followed the link natively — i.e. whether the hook
 * left the default alone. Read at the window, after the hook's listener has run, and then cancelled there so
 * jsdom does not attempt a navigation it cannot perform.
 */
function click(link: HTMLElement, init: MouseEventInit = {}): boolean {
  let followedNatively = false;
  const observe = (event: Event) => {
    followedNatively = !event.defaultPrevented;
    event.preventDefault();
  };
  window.addEventListener('click', observe);
  try {
    fireEvent.click(link, { button: 0, ...init });
  } finally {
    window.removeEventListener('click', observe);
  }
  return followedNatively;
}

describe('useRoutedLinks (core#169)', () => {
  it('keeps an internal link in the shell instead of reloading the page', () => {
    renderNotes('<a href="/feeds/other">other feed</a>');

    const followedNatively = click(screen.getByText('other feed'));

    expect(followedNatively).toBe(false);
    expect(screen.getByTestId('where')).toHaveTextContent('/feeds/other');
  });

  it('routes an absolute link to this origin too', () => {
    renderNotes(`<a href="${window.location.origin}/episodes/next">next</a>`);

    click(screen.getByText('next'));

    expect(screen.getByTestId('where')).toHaveTextContent('/episodes/next');
  });

  it('leaves a modified click to the browser — that is a request for a new tab', () => {
    renderNotes('<a href="/feeds/other">other feed</a>');

    expect(click(screen.getByText('other feed'), { ctrlKey: true })).toBe(true);
    expect(screen.getByTestId('where')).toHaveTextContent('/episodes/here');
  });

  it('leaves external links and server paths alone', () => {
    renderNotes(
      '<a href="https://example.com/x" target="_blank">ext</a><a href="/api/feeds">api</a>' +
        '<a href="/feed.xml">rss</a>',
    );

    expect(click(screen.getByText('ext'))).toBe(true);
    expect(click(screen.getByText('api'))).toBe(true);
    expect(click(screen.getByText('rss'))).toBe(true);
    expect(screen.getByTestId('where')).toHaveTextContent('/episodes/here');
  });
});
