// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../i18n';
import { SettingsFieldInput } from './SettingsFieldInput';

const OPTIONS = [
  { value: 'lines', label: 'Lines' },
  { value: 'fields', label: 'Fields' },
];

describe('SettingsFieldInput select (core#156)', () => {
  it('shows a value none of the options carries as "not chosen", not as the first option', () => {
    render(<SettingsFieldInput field={{ key: 'rankBy', type: 'SELECT', options: OPTIONS }} value="" onChange={vi.fn()} />);

    const select = screen.getByRole('combobox') as HTMLSelectElement;
    expect(select.selectedOptions[0]?.textContent).toBe('Not chosen');
  });

  it('shows a real value as itself', () => {
    render(
      <SettingsFieldInput field={{ key: 'rankBy', type: 'SELECT', options: OPTIONS }} value="fields" onChange={vi.fn()} />,
    );

    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('fields');
    expect(screen.queryByText('Not chosen')).not.toBeInTheDocument();
  });
});

describe('SettingsFieldInput bounds (SDK 0.16.0)', () => {
  it('renders a plugin number field’s declared bounds as input constraints', () => {
    render(
      <SettingsFieldInput
        field={{ key: 'interval', type: 'number', min: 10, max: 3600, step: 1 }}
        value="60"
        onChange={vi.fn()}
      />,
    );

    const input = screen.getByRole('spinbutton') as HTMLInputElement;
    expect([input.min, input.max, input.step]).toEqual(['10', '3600', '1']);
  });

  it('lets an unbounded plugin number take a decimal, and caps a string’s length', () => {
    const { rerender } = render(
      <SettingsFieldInput field={{ key: 'threshold', type: 'number' }} value="0.85" onChange={vi.fn()} />,
    );
    // Without a declared step the browser defaults to 1 and flags 0.85 as invalid.
    expect((screen.getByRole('spinbutton') as HTMLInputElement).step).toBe('any');

    rerender(
      <SettingsFieldInput field={{ key: 'greeting', type: 'string', maxLength: 80 }} value="Hi" onChange={vi.fn()} />,
    );
    expect((screen.getByRole('textbox') as HTMLInputElement).maxLength).toBe(80);
  });
});
