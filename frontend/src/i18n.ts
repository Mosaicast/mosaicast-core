// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import de from './locales/de.json';
import en from './locales/en.json';

/**
 * i18n bootstrap (ARCHITECTURE §12.7). English is the source language; German ships in v1. Locale
 * resolution order — explicit choice (persisted, works anonymously) → browser → site default.
 *
 * The languages an instance *has* are no longer decided here. The backend scans the shipped catalogs and
 * whatever an operator dropped into `MOSAICAST_LOCALES_DIR`, and `GET /api/i18n/locales` is the answer.
 * The two catalogs below stay compiled in so the languages that ship with the release render before any
 * network round trip; everything else is fetched on demand by {@link ensureCatalog}.
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

/** One language as the host describes it. */
export interface LocaleInfo {
  code: string;
  nativeName: string;
  isDefault: boolean;
}

/** The host's answer to "what languages does this site have". */
export interface LocaleList {
  defaultLocale: string;
  /** Languages the shell may render in. */
  ui: LocaleInfo[];
  /** Languages content may be authored in — legal pages, About, per-locale plugin content. */
  content: LocaleInfo[];
}

/** What the compiled-in catalogs guarantee, before (or without) a successful fetch. */
const BUILT_IN: LocaleList = {
  defaultLocale: 'en',
  ui: [
    { code: 'en', nativeName: 'English', isDefault: true },
    { code: 'de', nativeName: 'Deutsch', isDefault: false },
  ],
  content: [
    { code: 'en', nativeName: 'English', isDefault: true },
    { code: 'de', nativeName: 'Deutsch', isDefault: false },
  ],
};

let registry: LocaleList = BUILT_IN;

/** Catalogs already fetched and registered, so switching back and forth costs one request per language. */
const fetched = new Set<string>();

/**
 * Replaces the known language list. Exported for tests and for {@link loadLocales}; the shell calls it
 * exactly once, from the site provider.
 */
export function setLocaleRegistry(list: LocaleList): void {
  registry = {
    defaultLocale: list.defaultLocale || 'en',
    ui: list.ui?.length ? list.ui : BUILT_IN.ui,
    content: list.content?.length ? list.content : BUILT_IN.content,
  };
}

/**
 * Fetches the host's language list, then makes sure the active language's catalog is loaded.
 *
 * <p>Best-effort by design: a failure leaves the compiled-in catalogs in place, so a backend that is slow or
 * briefly unreachable costs an operator their drop-in languages, not their site.
 */
export async function loadLocales(fetcher: typeof fetch = fetch): Promise<void> {
  try {
    const response = await fetcher('/api/i18n/locales');
    if (!response.ok) {
      return;
    }
    setLocaleRegistry((await response.json()) as LocaleList);
    await ensureCatalog(currentLocale(), fetcher);
  } catch {
    /* keep the built-in catalogs */
  }
}

/**
 * Loads and registers one language's catalog if it is not compiled in and has not been fetched yet.
 *
 * Registered with `deep`/`overwrite` so a drop-in file that redefines three keys of a shipped language wins
 * on exactly those three — the same merge the backend does, applied on the same terms here.
 */
export async function ensureCatalog(code: string, fetcher: typeof fetch = fetch): Promise<void> {
  if (fetched.has(code) || !registry.ui.some((locale) => locale.code === code)) {
    return;
  }
  fetched.add(code);
  try {
    const response = await fetcher(`/api/i18n/catalog/${encodeURIComponent(code)}`);
    if (!response.ok) {
      return;
    }
    i18n.addResourceBundle(code, 'translation', await response.json(), true, true);
    if (currentLocale() === code) {
      // The bundle arrived after render; nudge i18next so mounted components re-read it.
      void i18n.changeLanguage(code);
    }
  } catch {
    /* a missing catalog falls back to English, which is what fallbackLng is for */
  }
}

/** The active language, as a bare code (`de`, not `de-DE`). */
export function currentLocale(): string {
  return i18n.language.slice(0, 2);
}

/** The site default — the last fallback for anything served per locale. */
export function defaultLocale(): string {
  return registry.defaultLocale;
}

/** The languages the shell offers. The single source of truth for the language menu. */
export function availableLocales(): string[] {
  return registry.ui.map((locale) => locale.code);
}

/** The languages content may be authored in — the legal editor's tabs, and a plugin's own editors. */
export function contentLocales(): string[] {
  return registry.content.map((locale) => locale.code);
}

/**
 * A human, native-language label for a locale code.
 *
 * The host's own name for the language wins; otherwise `Intl.DisplayNames` answers, which means a language
 * nobody here anticipated still gets a real name rather than a shouty code.
 */
export function localeName(code: string): string {
  const known = [...registry.ui, ...registry.content].find((locale) => locale.code === code);
  if (known?.nativeName) {
    return known.nativeName;
  }
  try {
    return new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code.toUpperCase();
  } catch {
    return code.toUpperCase();
  }
}

export default i18n;
