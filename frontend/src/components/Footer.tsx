// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import { MOSAICAST_REPO_URL } from '../api/constants';
import { useLegalEntries } from '../hooks/useLegalEntries';
import { useSite } from '../theme/SiteContext';

/**
 * The site footer (ARCHITECTURE §12.6): the site name, the core version (from `GET /api/meta`), and the
 * legal-page links (privacy/imprint/terms) resolved to the active locale. The same links also live in the
 * top-bar info menu so they are reachable from every page without scrolling to the bottom.
 */
export function Footer() {
  const { t } = useTranslation();
  const { site } = useSite();
  const legal = useLegalEntries();
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api
      .get<{ version?: string }>('/api/meta')
      .then((meta) => {
        if (active && meta.version) {
          setVersion(meta.version);
        }
      })
      .catch(() => {
        /* meta is non-critical */
      });
    return () => {
      active = false;
    };
  }, []);

  return (
    <footer className="mc-foot">
      <span>{site?.name ?? t('app.title')}</span>
      {legal.length > 0 && (
        <nav className="mc-foot__legal" aria-label={t('footer.legal')}>
          {legal.map((entry) => (
            <Link key={entry.slug} to={`/legal/${entry.slug}`}>
              {entry.title}
            </Link>
          ))}
        </nav>
      )}
      {/*
        Unconditional. It used to appear only once a plugin declared something, which left a core-only
        install with no way to reach the settings at all — even though the core stores a session cookie, a
        language and a playback position of its own (§12.5).
      */}
      <Link className="mc-foot__consent" to="/cookies">
        {t('consent.settings')}
      </Link>
      {version && (
        <span className="mc-foot__version">
          <a href={MOSAICAST_REPO_URL} target="_blank" rel="noreferrer noopener">
            {t('footer.poweredBy')} <code>{version}</code>
          </a>
        </span>
      )}
    </footer>
  );
}
