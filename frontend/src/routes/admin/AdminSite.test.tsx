// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { SiteView } from '../../api/types';
import { AdminSite } from './AdminSite';

const site: SiteView = {
  name: 'My Cast',
  modePolicy: 'dark',
  accentSeed: '#2e7d6b',
  defaultLocale: 'de',
  theme: {
    accentSeed: '#2e7d6b',
    light: {} as never,
    dark: {} as never,
  },
  branding: { logo: '/branding/logo', favicon: '/branding/favicon', darkLogo: null },
};

// The form must prefill from the loaded site config, not the light/default fallback.
vi.mock('../../theme/SiteContext', () => ({
  useSite: () => ({ site, mode: 'dark', refresh: vi.fn() }),
}));

describe('AdminSite', () => {
  it('prefills mode and accent seed from the saved site config', () => {
    render(<AdminSite />);
    const selects = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(selects.some((s) => s.value === 'dark')).toBe(true); // theme mode
    expect(screen.getByText('#2e7d6b')).toBeInTheDocument();
    expect((screen.getByDisplayValue('My Cast') as HTMLInputElement).value).toBe('My Cast');
  });

  // The default language moved to the languages page (§12.7), where it can be checked against the
  // languages content may be authored in. Two editors for one setting, only one of them validating, is
  // how a site ends up with a default nobody can write a legal page in.
  it('no longer edits the default language', () => {
    render(<AdminSite />);
    const selects = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect(selects.some((s) => s.value === 'de')).toBe(false);
  });
});
