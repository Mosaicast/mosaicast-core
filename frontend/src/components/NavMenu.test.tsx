// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import type { NavItem } from '../plugins/types';
import { NavMenu } from './NavMenu';

/**
 * The menu is mostly a set of absence rules, so that is what these cover: when it is there at all, and
 * whether the divider lands between groups rather than at an edge.
 */

let mockFeeds: { id: string; slug: string; title: string }[] = [];
let mockLoaded = true;

vi.mock('./FeedsContext', () => ({
  useFeeds: () => ({ feeds: mockFeeds, loaded: mockLoaded }),
}));

let mockEntries: NavItem[] = [];

vi.mock('../hooks/useResource', () => ({
  useResource: () => ({ data: mockEntries, loading: false, error: null, reload: () => {} }),
}));

function feed(slug: string, title: string) {
  return { id: slug, slug, title };
}

function entry(pluginId: string, label: string, icon: string | null = null): NavItem {
  return { pluginId, path: '', href: `/p/${pluginId}`, label, icon };
}

function open() {
  render(
    <MemoryRouter>
      <NavMenu />
    </MemoryRouter>,
  );
  const trigger = screen.queryByRole('button', { name: 'Site navigation' });
  if (trigger) {
    fireEvent.click(trigger);
  }
  return trigger;
}

/** Separators are the failure mode: a leading rule, or two in a row, both look "present". Count them. */
function separatorCount() {
  return screen.queryAllByRole('separator').length;
}

describe('NavMenu', () => {
  afterEach(() => {
    mockFeeds = [];
    mockEntries = [];
    mockLoaded = true;
  });

  it('does not exist when it would only hold a second link home', () => {
    mockFeeds = [feed('only', 'The Only Cast')];
    mockEntries = [];

    expect(open()).toBeNull();
  });

  it('stays away until the feeds have actually arrived', () => {
    // Before the fetch settles an empty list and a one-feed site look identical; appearing and then
    // vanishing is worse than arriving a moment late.
    mockLoaded = false;
    mockFeeds = [];
    mockEntries = [];

    expect(open()).toBeNull();
  });

  it('appears for a plugin page even on a single-feed site, with no separator', () => {
    mockFeeds = [feed('only', 'The Only Cast')];
    mockEntries = [entry('wiki', 'Wiki')];

    expect(open()).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Wiki' })).toHaveAttribute('href', '/p/wiki');
    expect(separatorCount()).toBe(0);
    // "All podcasts" would be a second link to where the brand already goes.
    expect(screen.queryByRole('menuitem', { name: 'All podcasts' })).toBeNull();
  });

  it('appears for multiple feeds with no plugins, with no separator', () => {
    mockFeeds = [feed('a', 'Cast A'), feed('b', 'Cast B')];
    mockEntries = [];

    expect(open()).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'All podcasts' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Cast B' })).toHaveAttribute('href', '/feeds/b');
    expect(separatorCount()).toBe(0);
  });

  it('puts exactly one separator between the two groups', () => {
    mockFeeds = [feed('a', 'Cast A'), feed('b', 'Cast B')];
    mockEntries = [entry('wiki', 'Wiki'), entry('recipes', 'Recipes')];

    open();

    expect(separatorCount()).toBe(1);
    // Both groups are named for assistive tech, not just visually.
    expect(screen.getByRole('group', { name: 'Podcasts' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Pages' })).toBeInTheDocument();
  });

  it('renders entries in the order the host resolved them', () => {
    mockFeeds = [];
    mockEntries = [entry('z-plugin', 'Zebra'), entry('a-plugin', 'Aardvark')];

    open();

    // Not re-sorted client-side: the host applied the admin's ordering and that is the answer.
    const labels = screen.getAllByRole('menuitem').map((item) => item.textContent);
    expect(labels).toEqual(['Zebra', 'Aardvark']);
  });

  it('still renders an entry whose icon name matches nothing', () => {
    mockFeeds = [];
    mockEntries = [entry('wiki', 'Wiki', 'not-a-real-icon')];

    open();

    expect(screen.getByRole('menuitem', { name: 'Wiki' })).toBeInTheDocument();
  });

  it('refuses an icon name that is not a plain token', () => {
    // The name reaches a CSS custom property, so anything that could close the `var(…)` is dropped rather
    // than escaped — there is no legitimate icon name that needs those characters.
    mockFeeds = [];
    mockEntries = [entry('evil', 'Evil', 'x); background: url(http://evil.test/beacon')];

    const { container } = render(
      <MemoryRouter>
        <NavMenu />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Site navigation' }));

    expect(screen.getByRole('menuitem', { name: 'Evil' })).toBeInTheDocument();
    expect(container.querySelector('.mc-navmenu__icon')).toBeNull();
    expect(container.innerHTML).not.toContain('evil.test');
  });
});
