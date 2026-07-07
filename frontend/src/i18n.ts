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

export default i18n;
