// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminPlugins } from './AdminPlugins';

const PLUGIN = {
  id: 'sample',
  status: 'LOADED',
  reason: null,
  name: 'Sample',
  version: '1.0.0',
  enabled: true,
  config: {
    refreshIntervalMinutes: {
      type: 'number',
      editableBy: 'podcaster',
      defaultValue: 30,
      value: 30,
      overridden: false,
    },
  },
  consent: { categories: [], externalSources: [] },
};

/** Records every request and answers each with the given plugin list. */
function stubFetch(plugins: unknown[] = [PLUGIN]) {
  const calls: Array<{ url: string; method: string; body: string | undefined }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
      const payload = url.startsWith('/api/admin/plugins?') || url === '/api/admin/plugins' ? plugins : {};
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
    }),
  );
  return calls;
}

describe('AdminPlugins (M5 E5c)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders a form input per declared config field and PUTs the typed value', async () => {
    const calls = stubFetch();
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    const input = screen.getByRole('spinbutton') as HTMLInputElement; // number → spinbutton
    expect(input.value).toBe('30');

    fireEvent.change(input, { target: { value: '5' } });
    fireEvent.click(screen.getByText('Save'));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT' && c.url === '/api/admin/plugins/sample/config');
      expect(put).toBeDefined();
      // The declared type decides the JSON: a number field must not send the raw input string.
      expect(put?.body).toBe(JSON.stringify({ refreshIntervalMinutes: 5 }));
    });
  });

  it('renders a declared set as a select, and sends the declared type', async () => {
    // A field whose plugin understands exactly three numbers was a free-text box, where a typo passed
    // validation, was stored, and then fell back silently at read time.
    const calls = stubFetch([
      {
        ...PLUGIN,
        config: {
          defaultGridSize: {
            type: 'number',
            editableBy: 'podcaster',
            defaultValue: 3,
            value: 3,
            overridden: false,
            options: [
              { value: 3, label: '3x3' },
              { value: 4, label: '4x4' },
              { value: 5, label: '5x5' },
            ],
          },
        },
      },
    ]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    const select = screen.getByRole('combobox') as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent)).toEqual(['3x3', '4x4', '5x5']);
    expect(select.value).toBe('3');

    fireEvent.change(select, { target: { value: '5' } });
    fireEvent.click(screen.getByText('Save'));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT' && c.url === '/api/admin/plugins/sample/config');
      // Only the rendering changed: a numeric set still goes out as a number, not the form's string.
      expect(put?.body).toBe(JSON.stringify({ defaultGridSize: 5 }));
    });
  });

  it('sends a boolean set as the value that was picked', async () => {
    // The form holds strings, and `Boolean('false')` is `true` — coercing the draft through the declared
    // type would have saved the opposite of the choice. The option carries the manifest's own value.
    const calls = stubFetch([
      {
        ...PLUGIN,
        config: {
          showTotals: {
            type: 'boolean',
            editableBy: 'admin',
            defaultValue: true,
            value: true,
            overridden: false,
            options: [
              { value: true, label: { en: 'Shown', de: 'Sichtbar' } },
              { value: false, label: { en: 'Hidden', de: 'Versteckt' } },
            ],
          },
        },
      },
    ]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    const select = screen.getByRole('combobox') as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent)).toEqual(['Shown', 'Hidden']);

    fireEvent.change(select, { target: { value: 'false' } });
    fireEvent.click(screen.getByText('Save'));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT' && c.url === '/api/admin/plugins/sample/config');
      expect(put?.body).toBe(JSON.stringify({ showTotals: false }));
    });
  });

  it('toggles activation through the enabled endpoint', async () => {
    const calls = stubFetch();
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox'));

    await waitFor(() => {
      expect(
        calls.some((c) => c.method === 'PUT' && c.url === '/api/admin/plugins/sample/enabled?value=false'),
      ).toBe(true);
    });
  });

  it('purges only after an explicit confirmation', async () => {
    const calls = stubFetch();
    vi.stubGlobal('confirm', vi.fn(() => false));
    vi.stubGlobal('alert', vi.fn());
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Purge data'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);

    vi.stubGlobal('confirm', vi.fn(() => true));
    fireEvent.click(screen.getByText('Purge data'));
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'POST' && c.url === '/api/admin/plugins/sample/purge')).toBe(true);
    });
  });

  it('marks a switched-off plugin and says its backend stops at the next restart', async () => {
    stubFetch([{ ...PLUGIN, enabled: false }]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Disabled')).toBeInTheDocument();
    expect(screen.getByText(/stops at the next restart/i)).toBeInTheDocument();
  });
});
