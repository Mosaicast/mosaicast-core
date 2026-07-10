// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import { useSite } from '../theme/SiteContext';

/**
 * The site footer (ARCHITECTURE §12.6): the site name, the core version (from `GET /api/meta`), and the
 * language switcher's companion note. Legal-page links (privacy/imprint/terms) and their admin CMS land
 * with the account/admin phases; this foundation footer establishes the region and the version readout.
 */
export function Footer() {
  const { t } = useTranslation();
  const { site } = useSite();
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
      {version && (
        <span className="mc-foot__version">
          {t('footer.version')} <code>{version}</code>
        </span>
      )}
    </footer>
  );
}
