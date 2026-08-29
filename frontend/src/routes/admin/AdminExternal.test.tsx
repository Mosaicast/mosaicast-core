// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { AdminKindSection } from '../../api/types';
import { AdminExternal } from './AdminExternal';

function section(overrides: Partial<AdminKindSection> = {}): AdminKindSection {
  return {
    kind: 'translation',
    selectedProviderId: 'stub',
    ready: true,
    missingSettings: [],
    encryptsSecrets: true,
    providers: [
      {
        id: 'stub',
        name: 'Stub',
        description: 'A test double.',
        homepage: null,
        privacyUrl: null,
        selfHosted: true,
        paid: false,
        thirdCountryTransfer: false,
        fields: [
          {
            key: 'baseUrl', type: 'STRING', label: 'Base URL', description: '',
            defaultValue: null, value: 'http://localhost:5000', overridden: true, required: true,
            set: true, min: null, max: null, options: [], envVar: null, placeholder: null,
          },
          {
            key: 'apiKey', type: 'SECRET', label: 'API key', description: 'Only if enforced.',
            defaultValue: null, value: null, overridden: false, required: false,
            set: false, min: null, max: null, options: [], envVar: null, placeholder: null,
          },
          {
            key: 'adminKey', type: 'ENV_SECRET', label: 'Admin key', description: '',
            defaultValue: null, value: null, overridden: false, required: false,
            set: false, min: null, max: null, options: [],
            envVar: 'MOSAICAST_EXTERNAL_TRANSLATION_STUB_ADMIN_KEY', placeholder: null,
          },
          {
            key: 'requestsPerMinute', type: 'INTEGER', label: 'Requests per minute', description: '',
            defaultValue: 60, value: null, overridden: false, required: false,
            set: false, min: 1, max: null, options: [], envVar: null, placeholder: null,
          },
          {
            key: 'format', type: 'SELECT', label: 'Format', description: '',
            defaultValue: 'text', value: null, overridden: false, required: false,
            set: false, min: null, max: null,
            options: [{ value: 'text', label: 'Plain text' }, { value: 'html', label: 'HTML' }],
            envVar: null, placeholder: null,
          },
        ],
      },
    ],
    ...overrides,
  };
}

const put = vi.fn(async (_path: string, _body: unknown) => section());
const post = vi.fn(async () => ({ ok: true, detail: 'reachable', millis: 12 }));
let payload: AdminKindSection[] = [section()];

vi.mock('../../api/client', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    get: vi.fn(async () => payload),
    put: (path: string, body: unknown) => put(path, body),
    post: () => post(),
  },
}));

describe('AdminExternal', () => {
  beforeEach(() => {
    put.mockClear();
    post.mockClear();
    payload = [section()];
  });

  it('renders each declared field with the control its type calls for', async () => {
    render(<AdminExternal />);

    expect(await screen.findByDisplayValue('http://localhost:5000')).toBeInTheDocument();
    // A number field gets bounds, not just a text box.
    const rpm = screen.getByRole<HTMLInputElement>('spinbutton', { name: /Requests per minute/ });
    expect(rpm.min).toBe('1');
    expect(screen.getByRole('combobox', { name: /Format/ })).toBeInTheDocument();
  });

  it('gives an env-backed credential no input at all, just the variable name', async () => {
    render(<AdminExternal />);
    await screen.findByDisplayValue('http://localhost:5000');

    // There is nothing to type into: the value lives in the environment.
    expect(screen.queryByRole('textbox', { name: /Admin key/ })).toBeNull();
    expect(screen.getByText('MOSAICAST_EXTERNAL_TRANSLATION_STUB_ADMIN_KEY')).toBeInTheDocument();
  });

  it('does not send a blank credential, so an untouched box keeps the stored one', async () => {
    // The write-only box is empty on every load. Treating that as "clear it" would wipe a working key
    // the first time an admin saved an unrelated field.
    render(<AdminExternal />);
    await screen.findByDisplayValue('http://localhost:5000');

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    const [, body] = put.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(body).not.toHaveProperty('apiKey');
  });

  it('sends a credential the admin actually typed', async () => {
    render(<AdminExternal />);
    await screen.findByDisplayValue('http://localhost:5000');

    fireEvent.change(screen.getByLabelText(/API key/), { target: { value: 'sk-live-1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    const [, body] = put.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(body.apiKey).toBe('sk-live-1234');
  });

  it('warns when stored credentials would not be encrypted', async () => {
    payload = [section({ encryptsSecrets: false })];
    render(<AdminExternal />);

    expect(await screen.findByText(/MOSAICAST_ENCRYPTION_KEY is not set/)).toBeInTheDocument();
  });

  it('disables the test button until the host says a call would be attempted', async () => {
    payload = [section({ ready: false, missingSettings: ['baseUrl'] })];
    render(<AdminExternal />);

    const test = await screen.findByRole<HTMLButtonElement>('button', { name: 'Test' });
    expect(test.disabled).toBe(true);
    expect(screen.getByText(/Still needs: baseUrl/)).toBeInTheDocument();
  });

  it('offers "None", because selecting nothing is a real choice', async () => {
    render(<AdminExternal />);

    const provider = await screen.findByRole('combobox', { name: /Provider/ });
    expect(provider).toHaveTextContent('None');

    fireEvent.change(provider, { target: { value: '' } });
    await waitFor(() => expect(put).toHaveBeenCalled());
    const [path, body] = put.mock.calls[0] as unknown as [string, Record<string, unknown>];
    expect(path).toBe('/api/admin/external/translation/provider');
    expect(body.providerId).toBeNull();
  });
});
