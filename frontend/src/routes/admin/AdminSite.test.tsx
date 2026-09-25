// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
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
    expect(screen.getByLabelText('Accent colour as hex')).toHaveValue('#2e7d6b');
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

  it('can upload every branding asset from the keyboard (core#163)', () => {
    // `hidden` took the file inputs out of the tab order, and a label is not focusable: no logo, favicon or
    // dark logo without a mouse.
    const { container } = render(<AdminSite />);
    const inputs = [...container.querySelectorAll<HTMLInputElement>('input[type="file"]')];

    expect(inputs.length).toBeGreaterThanOrEqual(3);
    for (const input of inputs) {
      expect(input.hidden).toBe(false);
      expect(input.tabIndex).toBe(0);
      input.focus();
      expect(document.activeElement).toBe(input);
      // Named by its label and told what it accepts.
      expect(input.closest('label')).toHaveTextContent('Upload');
      expect(input).toHaveAccessibleDescription(/PNG, JPEG, WEBP or ICO, up to 2 MB/);
    }
  });

  it('warns while choosing an accent that would be unreadable as text (core#162)', () => {
    const original = site.theme;
    site.theme = {
      accentSeed: site.accentSeed,
      light: { bg: '#fbf8f3' } as never,
      dark: { bg: '#17140f' } as never,
    };
    try {
      render(<AdminSite />);
      expect(screen.queryByText(/hard to read as text/)).not.toBeInTheDocument();

      fireEvent.change(container().querySelector('input[type="color"]')!, { target: { value: '#fff176' } });

      expect(screen.getByText(/hard to read as text/)).toBeInTheDocument();
    } finally {
      site.theme = original;
    }
  });
});

describe('AdminSite accent hex (#199)', () => {
  it('takes a pasted brand colour, and refuses to save one that is not whole', () => {
    render(<AdminSite />);
    const hex = screen.getByLabelText('Accent colour as hex');
    const swatch = container().querySelector('input[type="color"]') as HTMLInputElement;

    fireEvent.change(hex, { target: { value: '1A5FB4' } });
    expect(swatch.value).toBe('#1a5fb4');
    expect(screen.getByRole('button', { name: 'Save & preview' })).toBeEnabled();

    fireEvent.change(hex, { target: { value: '#1a5f' } });
    expect(screen.getByText(/six hex digits/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save & preview' })).toBeDisabled();
    // The swatch keeps the last whole colour rather than guessing at a partial one.
    expect(swatch.value).toBe('#1a5fb4');
  });
});

const container = () => document.body;
