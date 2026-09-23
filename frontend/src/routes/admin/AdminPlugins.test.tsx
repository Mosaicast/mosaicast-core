// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminPlugins } from './AdminPlugins';

/** Who is looking. A podcaster reaches this page for delegated fields; everything else here is ADMIN. */
let role: 'admin' | 'podcaster' = 'admin';
vi.mock('../../auth/UserContext', () => ({
  useUser: () => ({ user: { id: 'me', displayName: 'Me', avatarUrl: null, role } }),
}));

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
  beforeEach(() => {
  role = 'admin';
});

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

  it('shows a podcaster the delegated fields and none of the admin controls', async () => {
    // Reading the list used to be ADMIN while writing a field was open to PODCASTER, so `editableBy:
    // "podcaster"` rendered in the UI and was unreachable in practice.
    role = 'podcaster';
    stubFetch([
      {
        ...PLUGIN,
        // What the server sends a podcaster: the admin-only row keeps its shape and loses its value.
        config: {
          refreshIntervalMinutes: PLUGIN.config.refreshIntervalMinutes,
          apiToken: {
            type: 'string',
            editableBy: 'admin',
            defaultValue: null,
            value: null,
            overridden: false,
          },
        },
      },
    ]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    // The delegated field is there to edit, and so is the row they may only look at.
    expect(screen.getByText(/refreshIntervalMinutes/)).toBeInTheDocument();
    expect(screen.getByText(/apiToken/)).toBeInTheDocument();
    // Everything the server would refuse is absent rather than rendered to fail.
    expect(screen.queryByText('Purge data')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();

    // And the admin-only row is readable, not editable: a save is all-or-nothing, so typing into it would
    // cost the podcaster the edits they were allowed to make.
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[];
    expect(inputs.some((i) => i.disabled)).toBe(true);
  });

  it('reads a field by its label, in the language the operator is using', async () => {
    stubFetch([
      {
        ...PLUGIN,
        config: {
          refreshIntervalMinutes: {
            ...PLUGIN.config.refreshIntervalMinutes,
            label: { en: 'Refresh interval', de: 'Aktualisierungsintervall' },
            description: { en: 'How often the feed is re-read, in minutes.', de: 'Wie oft neu gelesen wird.' },
          },
          untitled: {
            type: 'string',
            editableBy: 'admin',
            defaultValue: 'x',
            value: 'x',
            overridden: false,
          },
        },
      },
    ]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Refresh interval')).toBeInTheDocument();
    expect(screen.getByText('How often the feed is re-read, in minutes.')).toBeInTheDocument();
    // The key stays visible beside the label: a plugin's own docs name the identifier, not the prose.
    expect(screen.getByText('refreshIntervalMinutes')).toBeInTheDocument();
    // A field that declares nothing reads exactly as it did before.
    expect(screen.getByText(/untitled/)).toBeInTheDocument();
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

  it('purges only after the plugin id has been typed', async () => {
    // The app's own dialog with a typed word, not window.confirm: this is the most destructive action in
    // the product, and it was the one with the weakest gate (core#193). The word is the plugin's own id,
    // so the muscle memory of purging one does not carry over to purging another.
    const calls = stubFetch();
    render(<AdminPlugins />);

    expect(await screen.findByText('Sample')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Purge data'));

    // Opening the dialog asks for nothing yet, and the confirm button will not act on an empty field.
    const confirmButton = screen.getAllByText('Purge data').at(-1)!;
    fireEvent.click(confirmButton);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'sample' } });
    fireEvent.click(screen.getAllByText('Purge data').at(-1)!);
    await waitFor(() => {
      expect(
        calls.some(
          (c) => c.method === 'POST' && c.url === '/api/admin/plugins/sample/purge?confirm=sample',
        ),
      ).toBe(true);
    });
  });

  it('marks a switched-off plugin and says its backend stops at the next restart', async () => {
    stubFetch([{ ...PLUGIN, enabled: false }]);
    render(<AdminPlugins />);

    expect(await screen.findByText('Disabled')).toBeInTheDocument();
    expect(screen.getByText(/stops at the next restart/i)).toBeInTheDocument();
  });
});
