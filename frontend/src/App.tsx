// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { PLATFORM_API_VERSION } from '@mosaicast/plugin-sdk';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * M0 walking-skeleton shell. Proves the SPA renders, i18n works, and the built plugin SDK resolves
 * from this repo (importing {@link PLATFORM_API_VERSION}). The real chrome, player, feed and plugin
 * slots land in M5 (ARCHITECTURE §6, §7.5).
 */
export default function App() {
  const { t, i18n } = useTranslation();
  const [coreVersion, setCoreVersion] = useState<string | null>(null);

  // The core version is served by the backend (single source: gradle.properties → /api/meta).
  useEffect(() => {
    let active = true;
    fetch('/api/meta')
      .then((r) => (r.ok ? r.json() : null))
      .then((data: { version?: string } | null) => {
        if (active && data?.version) {
          setCoreVersion(data.version);
        }
      })
      .catch(() => {
        /* meta is non-critical; leave it unset */
      });
    return () => {
      active = false;
    };
  }, []);

  const toggleLocale = () => {
    const next = i18n.language.startsWith('de') ? 'en' : 'de';
    void i18n.changeLanguage(next);
    localStorage.setItem('mc.locale', next);
  };

  return (
    <main className="shell">
      <header className="shell__bar">
        <span className="shell__brand">
          {/* Default branding: the Mosaicast mark (theme-safe on light & dark). Replaced by the
              admin's uploaded logo once branding lands (M3), served from /branding/logo. */}
          <img className="shell__mark" src="/brand/mosaicast-mark.svg" alt="" aria-hidden="true" />
          {t('app.title')}
        </span>
        <button type="button" className="shell__lang" onClick={toggleLocale}>
          {i18n.language.startsWith('de') ? 'EN' : 'DE'}
        </button>
      </header>
      <section className="shell__body">
        <h1>{t('app.tagline')}</h1>
        <p>{t('skeleton.booting')}</p>
        <p className="shell__muted">
          {t('skeleton.coreVersion')}: <code>{coreVersion ?? '…'}</code>
          {'  ·  '}
          {t('skeleton.platformApi')}: <code>{PLATFORM_API_VERSION}</code>
        </p>
      </section>
    </main>
  );
}
