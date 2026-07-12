// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
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
      {version && (
        <span className="mc-foot__version">
          {t('footer.version')} <code>{version}</code>
        </span>
      )}
    </footer>
  );
}
