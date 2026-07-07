// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { PLATFORM_API_VERSION } from '@mosaicast/plugin-sdk';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import App from './App';
import './i18n';

describe('App shell (M0 skeleton)', () => {
  it('renders the brand and the resolved plugin SDK version', () => {
    render(<App />);
    expect(screen.getByText('Mosaicast')).toBeInTheDocument();
    // Proves the built @mosaicast/plugin-sdk resolves and renders into the shell.
    expect(screen.getByText(PLATFORM_API_VERSION)).toBeInTheDocument();
  });
});
