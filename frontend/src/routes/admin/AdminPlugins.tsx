// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AdminPlugin } from '../../plugins/types';

/**
 * The admin plugin surface (ARCHITECTURE §7.8): every discovered plugin and its load state — loaded, or
 * rejected with a reason (incompatible `platformApi`, declared schema, bad manifest). Read-only in this
 * milestone; activation toggles land with the config UI. Backed by `GET /api/admin/plugins` (ADMIN).
 */
export function AdminPlugins() {
  const { t } = useTranslation();
  const [plugins, setPlugins] = useState<AdminPlugin[] | null>(null);

  useEffect(() => {
    api
      .get<AdminPlugin[]>('/api/admin/plugins')
      .then(setPlugins)
      .catch(() => setPlugins([]));
  }, []);

  if (plugins == null) {
    return <p className="mc-muted">{t('common.loading')}</p>;
  }

  const rejected = plugins.filter((p) => p.status === 'REJECTED');

  return (
    <div className="mc-form">
      <h2>{t('admin.plugins.title')}</h2>
      {rejected.length > 0 && (
        <div className="mc-banner mc-banner--error" role="alert">
          <span>{t('admin.plugins.rejectedWarning', { count: rejected.length })}</span>
        </div>
      )}
      {plugins.length === 0 ? (
        <p className="mc-muted">{t('admin.plugins.empty')}</p>
      ) : (
        <ul className="mc-list">
          {plugins.map((plugin) => (
            <li key={plugin.id} className="mc-list__row">
              <div>
                <span className="mc-identity__provider">{plugin.name ?? plugin.id}</span>{' '}
                <span className="mc-muted">
                  {plugin.id}
                  {plugin.version ? ` · ${plugin.version}` : ''}
                </span>
                {plugin.reason && <p className="mc-error">{plugin.reason}</p>}
              </div>
              <span className={plugin.status === 'LOADED' ? 'mc-chip' : 'mc-chip mc-chip--lock'}>
                {plugin.status === 'LOADED' ? t('admin.plugins.loaded') : t('admin.plugins.rejected')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
