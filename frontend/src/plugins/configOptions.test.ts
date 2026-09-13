// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { localizedText, optionLabel } from './types';

/**
 * A config option's label is the one manifest string the host localises, and it is resolved here rather
 * than on the server — which knows neither the language the operator reads in nor when they switch it.
 */
describe('optionLabel', () => {
  it('takes the label for the reader’s locale', () => {
    const option = { value: 'lines', label: { en: 'Lines', de: 'Reihen' } };

    expect(optionLabel(option, 'de')).toBe('Reihen');
    expect(optionLabel(option, 'en')).toBe('Lines');
  });

  it('falls back from a regional locale to its base language', () => {
    // A manifest writes `de`; an operator reading `de-AT` should not drop through to English for it.
    expect(optionLabel({ value: 'lines', label: { en: 'Lines', de: 'Reihen' } }, 'de-AT')).toBe('Reihen');
  });

  it('falls back to English, then to any label there is', () => {
    expect(optionLabel({ value: 'lines', label: { en: 'Lines' } }, 'fr')).toBe('Lines');
    expect(optionLabel({ value: 'lines', label: { fr: 'Lignes' } }, 'de')).toBe('Lignes');
  });

  it('accepts a plain string, exactly like a nav or consent label', () => {
    expect(optionLabel({ value: 3, label: '3x3' }, 'de')).toBe('3x3');
  });

  it('shows the raw value rather than nothing when a label is missing or blank', () => {
    expect(optionLabel({ value: 'lines' }, 'de')).toBe('lines');
    expect(optionLabel({ value: 'lines', label: {} }, 'de')).toBe('lines');
    expect(optionLabel({ value: 'lines', label: { de: '   ' } }, 'de')).toBe('lines');
    expect(optionLabel({ value: 5, label: { de: '' } }, 'de')).toBe('5');
  });
});

/**
 * The same resolution, used for a config field's own prose. It answers `undefined` rather than a fallback
 * string, because what "nothing" means differs by caller: an option shows its raw value, a field its key.
 */
describe('localizedText', () => {
  it('takes the locale, then the base language, then English', () => {
    const label = { en: 'Refresh interval', de: 'Aktualisierungsintervall' };

    expect(localizedText(label, 'de')).toBe('Aktualisierungsintervall');
    expect(localizedText(label, 'de-AT')).toBe('Aktualisierungsintervall');
    expect(localizedText(label, 'fr')).toBe('Refresh interval');
  });

  it('accepts the plain string form every verbatim manifest label already uses', () => {
    expect(localizedText('Refresh interval', 'de')).toBe('Refresh interval');
  });

  it('says nothing rather than something empty', () => {
    expect(localizedText(undefined, 'de')).toBeUndefined();
    expect(localizedText({}, 'de')).toBeUndefined();
    expect(localizedText({ de: '   ' }, 'de')).toBeUndefined();
  });
});
