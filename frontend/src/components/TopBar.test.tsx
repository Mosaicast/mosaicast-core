// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import '../i18n';
import { TopBar } from './TopBar';

/**
 * The header's phone layout is CSS, which jsdom does not evaluate — what is testable here is the DOM the
 * CSS then picks from: the languages have to exist inside the info menu, because below 560px that copy is
 * the only one shown and the standalone control is hidden.
 */

vi.mock('./NavMenu', () => ({ NavMenu: () => null }));
vi.mock('./SlotRegion', () => ({ SlotRegion: () => null }));
vi.mock('./NotificationBell', () => ({ NotificationBell: () => null }));
vi.mock('./Avatar', () => ({ Avatar: () => null }));
vi.mock('../routes/SearchPage', () => ({ SearchLink: () => null }));
vi.mock('../hooks/useLegalEntries', () => ({ useLegalEntries: () => [] }));
vi.mock('../api/MetaContext', () => ({ useMeta: () => ({ devLoginEnabled: false }) }));
vi.mock('../theme/SiteContext', () => ({
  useSite: () => ({ site: { name: 'Sample', branding: { logo: '/branding/logo', darkLogo: null } }, mode: 'light' }),
}));
vi.mock('../auth/UserContext', () => ({
  useUser: () => ({ user: null, refresh: vi.fn(), logout: vi.fn() }),
}));

function renderBar() {
  render(
    <MemoryRouter>
      <TopBar />
    </MemoryRouter>,
  );
}

describe('TopBar', () => {
  it('offers the languages inside the info menu, where the phone layout reads them', () => {
    renderBar();

    fireEvent.click(screen.getByRole('button', { name: 'Legal & information' }));
    const panel = screen.getAllByRole('menu').at(-1) as HTMLElement;

    // The heading names what the group is; without it the languages read as two more legal pages.
    expect(panel).toHaveTextContent('Switch language');
    expect(panel.querySelector('.mc-menu__onphone')).not.toBeNull();
    expect([...panel.querySelectorAll('.mc-menu__onphone [role="menuitem"]')].map((e) => e.textContent))
      .toEqual(['English', 'Deutsch']);
  });

  it('keeps the standalone language control for the widths that have room for it', () => {
    renderBar();

    // Hidden below 560px in CSS, so it must still be in the DOM — and it must be the one that is hidden,
    // not the header's only switcher.
    const trigger = screen.getByRole('button', { name: 'Switch language' });
    expect(trigger.closest('.mc-top__lang')).not.toBeNull();
  });
});
