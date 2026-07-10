// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import type { GeneratedTheme, ThemeTokenSet } from '../api/types';
import { applyTheme, resolveMode } from './applyTheme';

const tokens = (accent: string): ThemeTokenSet => ({
  bg: '#000001',
  surface: '#000002',
  text: '#000003',
  textMuted: '#000004',
  accent,
  accentContrast: '#000006',
  border: '#000007',
  accent2: '#000008',
});

const theme: GeneratedTheme = {
  accentSeed: '#abcdef',
  light: tokens('#lightac'.slice(0, 7)),
  dark: tokens('#darkac0'.slice(0, 7)),
};

describe('applyTheme', () => {
  it('writes the mode tokens to :root and stamps data-theme', () => {
    applyTheme(theme, 'dark');
    const root = document.documentElement;
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(root.style.getPropertyValue('--mc-bg')).toBe('#000001');
    expect(root.style.getPropertyValue('--mc-accent')).toBe(theme.dark.accent);
    expect(root.style.getPropertyValue('--mc-accent-2')).toBe('#000008');
  });

  it('switches token values when the mode changes', () => {
    applyTheme(theme, 'light');
    const root = document.documentElement;
    expect(root.getAttribute('data-theme')).toBe('light');
    expect(root.style.getPropertyValue('--mc-accent')).toBe(theme.light.accent);
  });
});

describe('resolveMode', () => {
  it('returns an explicit policy unchanged', () => {
    expect(resolveMode('light')).toBe('light');
    expect(resolveMode('dark')).toBe('dark');
  });

  it('resolves system against the OS preference (matchMedia stubbed to light)', () => {
    expect(resolveMode('system')).toBe('light');
  });
});
