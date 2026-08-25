// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { AdminLocalesView } from '../../api/types';
import { AdminLanguages } from './AdminLanguages';

const view: AdminLocalesView = {
  sourceLocale: 'en',
  dropInDir: '/srv/mosaicast/locales',
  locales: [
    {
      code: 'de',
      nativeName: 'Deutsch',
      origin: 'BUNDLED',
      uiEnabled: true,
      contentEnabled: true,
      isDefault: false,
      keyCount: 388,
      missingKeys: 0,
    },
    {
      code: 'en',
      nativeName: 'English',
      origin: 'BUNDLED',
      uiEnabled: true,
      contentEnabled: true,
      isDefault: true,
      keyCount: 388,
      missingKeys: 0,
    },
    {
      code: 'nl',
      nativeName: 'Nederlands',
      origin: 'DROP_IN',
      uiEnabled: false,
      contentEnabled: false,
      isDefault: false,
      keyCount: 300,
      missingKeys: 88,
    },
    {
      code: 'fr',
      nativeName: 'français',
      origin: 'NONE',
      uiEnabled: false,
      contentEnabled: true,
      isDefault: false,
      keyCount: 0,
      missingKeys: 388,
    },
  ],
};

const put = vi.fn(async (_path: string, _body: unknown) => view);

vi.mock('../../api/client', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    get: vi.fn(async () => view),
    put: (path: string, body: unknown) => put(path, body),
  },
}));

vi.mock('../../i18n', async () => {
  const actual = await vi.importActual<typeof import('../../i18n')>('../../i18n');
  return { ...actual, loadLocales: vi.fn(async () => {}) };
});

describe('AdminLanguages', () => {
  beforeEach(() => put.mockClear());

  it('lists every detected language with its origin and translation debt', async () => {
    render(<AdminLanguages />);

    expect(await screen.findByText('Nederlands')).toBeInTheDocument();
    expect(screen.getByText('Dropped in')).toBeInTheDocument();
    expect(screen.getByText('88 strings missing')).toBeInTheDocument();
    // A content-only language has no catalog and says so rather than claiming zero strings.
    expect(screen.getByText('No catalog — content only')).toBeInTheDocument();
  });

  it('cannot switch the source language off, or offer a language with no catalog in the shell', async () => {
    render(<AdminLanguages />);

    const english = await screen.findByRole<HTMLInputElement>('checkbox', {
      name: 'Offer English in the shell',
    });
    expect(english.disabled).toBe(true);
    // French is enabled for content but has no catalog: nothing to render the shell with.
    const french = screen.getByRole<HTMLInputElement>('checkbox', {
      name: 'Offer français in the shell',
    });
    expect(french.disabled).toBe(true);
    expect(french.checked).toBe(false);
  });

  it('saves the two lists and the default separately', async () => {
    render(<AdminLanguages />);

    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Offer Nederlands in the shell' }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    const [path, body] = put.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(path).toBe('/api/admin/i18n');
    expect(body.uiLocales).toEqual(['de', 'en', 'nl']);
    expect(body.contentLocales).toEqual(['de', 'en', 'fr']);
    expect(body.defaultLocale).toBe('en');
  });
});
