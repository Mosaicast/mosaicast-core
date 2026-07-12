// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import de from './locales/de.json';
import en from './locales/en.json';

/**
 * i18n bootstrap (ARCHITECTURE §12.7). English is the source language; German ships in v1.
 * Locale resolution order — explicit choice (persisted, works anonymously) → browser → site default —
 * is layered on in M5; this M0 setup wires the catalogs and a persisted explicit choice.
 */
const stored = typeof localStorage !== 'undefined' ? localStorage.getItem('mc.locale') : null;
const browser = typeof navigator !== 'undefined' ? navigator.language.split('-')[0] : 'en';

void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    de: { translation: de },
  },
  lng: stored ?? browser,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

/** Native display names for locales we might ship; unknown codes fall back to the uppercased code. */
const LOCALE_NAMES: Record<string, string> = {
  en: 'English',
  de: 'Deutsch',
  es: 'Español',
  fr: 'Français',
  it: 'Italiano',
  nl: 'Nederlands',
  pt: 'Português',
};

/** The locales actually registered in i18n — the single source of truth for language menus and legal tabs. */
export function availableLocales(): string[] {
  return Object.keys(i18n.options.resources ?? {});
}

/** A human, native-language label for a locale code. */
export function localeName(code: string): string {
  return LOCALE_NAMES[code] ?? code.toUpperCase();
}

export default i18n;
