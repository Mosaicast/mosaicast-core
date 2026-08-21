// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AdminNavItem } from '../../plugins/types';

/**
 * Admin → Navigation: which plugin entries appear in the site menu, and in what order (ARCHITECTURE §7.3).
 *
 * Every entry every installed plugin declares is listed here, including ones currently switched off — an
 * admin cannot bring back something the page will not show them. Feeds are deliberately absent: they are
 * managed under Feeds and the menu lists whichever ones exist, so there is nothing to decide here.
 *
 * Saved as a whole list rather than row by row. An ordering only means anything as a set, and per-row saves
 * would let two entries settle on the same number with nothing to say which was intended.
 */
export function AdminNavigation() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<AdminNavItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const load = () => {
    api
      .get<AdminNavItem[]>('/api/admin/navigation')
      .then(setEntries)
      .catch(() => setError(t('admin.navigation.loadFailed')));
  };

  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  const edit = (index: number, patch: Partial<AdminNavItem>) => {
    setEntries((current) =>
      current == null ? current : current.map((entry, i) => (i === index ? { ...entry, ...patch } : entry)),
    );
    setSaved(false);
  };

  const save = async () => {
    if (entries == null) {
      return;
    }
    setError(null);
    try {
      const updated = await api.put<AdminNavItem[]>(
        '/api/admin/navigation',
        entries.map((e) => ({ pluginId: e.pluginId, path: e.path, enabled: e.enabled, order: e.order })),
      );
      // Re-render from the server's answer, not from local state: it is the side that resolved the order,
      // so a tie broken there should be visible here rather than only after a reload.
      setEntries(updated);
      setSaved(true);
    } catch {
      setError(t('admin.navigation.saveFailed'));
    }
  };

  if (error != null && entries == null) {
    return <p className="mc-error">{error}</p>;
  }
  if (entries == null) {
    return <p className="mc-muted">{t('common.loading')}</p>;
  }

  return (
    <section>
      <h2>{t('admin.navigation.title')}</h2>
      <p className="mc-muted">{t('admin.navigation.intro')}</p>

      {entries.length === 0 ? (
        <p className="mc-muted">{t('admin.navigation.empty')}</p>
      ) : (
        <>
          <div className="mc-tablewrap">
            <table className="mc-about__table">
              <thead>
                <tr>
                  <th scope="col">{t('admin.navigation.colEntry')}</th>
                  <th scope="col">{t('admin.navigation.colPlugin')}</th>
                  <th scope="col">{t('admin.navigation.colVisibleTo')}</th>
                  <th scope="col">{t('admin.navigation.colOrder')}</th>
                  <th scope="col">{t('admin.plugins.enabled')}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry, index) => (
                  <tr key={`${entry.pluginId}:${entry.path}`}>
                    <th scope="row">
                      {entry.label}
                      <div className="mc-muted">
                        <code>{entry.href}</code>
                      </div>
                    </th>
                    <td>{entry.pluginName}</td>
                    {/* Everyone, when a plugin named no floor — the same default a slot gets. */}
                    <td>{entry.visibleTo ?? t('admin.navigation.everyone')}</td>
                    <td>
                      <input
                        className="mc-input mc-input--num"
                        type="number"
                        aria-label={t('admin.navigation.orderOf', { label: entry.label })}
                        value={entry.order}
                        onChange={(e) => edit(index, { order: Number(e.target.value) })}
                      />
                    </td>
                    <td>
                      <label className="mc-toggle">
                        <input
                          type="checkbox"
                          checked={entry.enabled}
                          onChange={(e) => edit(index, { enabled: e.target.checked })}
                        />
                        <span className="mc-sr-only">
                          {t('admin.navigation.enabledOf', { label: entry.label })}
                        </span>
                      </label>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mc-form__actions">
            <button type="button" className="mc-btn mc-btn--accent" onClick={() => void save()}>
              {t('common.save')}
            </button>
            {saved && <span className="mc-muted">{t('admin.plugins.saved')}</span>}
            {error != null && <span className="mc-error">{error}</span>}
          </div>

          {/* Stated rather than discovered: a label comes from the plugin's manifest as written. */}
          <p className="mc-muted">{t('admin.navigation.labelsNote')}</p>
        </>
      )}
    </section>
  );
}
