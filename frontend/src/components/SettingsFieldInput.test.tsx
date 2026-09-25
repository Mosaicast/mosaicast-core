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
