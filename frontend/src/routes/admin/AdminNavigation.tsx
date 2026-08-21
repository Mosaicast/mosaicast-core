// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AdminNavItem } from '../../plugins/types';

/**
 * Admin → Navigation: which plugin entries appear in the site menu, and in what order (ARCHITECTURE §7.3).
 *
 * Every entry every installed plugin declares is listed, including ones currently switched off — an admin
 * cannot bring back something the page will not show them. Feeds are deliberately absent: they are managed
 * under Feeds, and the menu lists whichever ones exist, so there is nothing to decide here.
 *
 * **Order is the row's position, not a number anyone types.** Rows drag for pointer users and move with the
 * buttons for everyone else. Both matter: HTML5 drag events never fire on touch and are invisible to a
 * screen reader, so drag alone would make this page unusable for most of the ways people reach it — the
 * buttons are the real mechanism and dragging is the shortcut.
 *
 * Saved as a whole list rather than row by row. An ordering only means anything as a set, and per-row saves
 * would let two entries settle on the same position with nothing to say which was intended.
 */
export function AdminNavigation() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<AdminNavItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  /** Announced to a screen reader after a move — the visual reorder alone tells them nothing. */
  const [announcement, setAnnouncement] = useState('');
  const dragFrom = useRef<number | null>(null);

  const load = () => {
    api
      .get<AdminNavItem[]>('/api/admin/navigation')
      .then(setEntries)
      .catch(() => setError(t('admin.navigation.loadFailed')));
  };

  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  const setEnabled = (index: number, enabled: boolean) => {
    setEntries((current) =>
      current == null ? current : current.map((entry, i) => (i === index ? { ...entry, enabled } : entry)),
    );
    setSaved(false);
  };

  /** Moves the row at `from` to `to`, and says so out loud. */
  const move = (from: number, to: number) => {
    setEntries((current) => {
      if (current == null || to < 0 || to >= current.length || from === to) {
        return current;
      }
      const next = [...current];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      setAnnouncement(
        t('admin.navigation.moved', { label: moved.label, position: to + 1, total: next.length }),
      );
      return next;
    });
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
        // Position is the order. Sending the index rather than a stored number means the list can never
        // disagree with itself — there is no second place for the ordering to live.
        entries.map((e, index) => ({
          pluginId: e.pluginId,
          path: e.path,
          enabled: e.enabled,
          order: index,
        })),
      );
      // Re-render from the server's answer: it resolved the order, so a tie it broke should be visible
      // here rather than only after a reload.
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
          <p className="mc-muted">{t('admin.navigation.reorderHint')}</p>

          <div className="mc-tablewrap">
            <table className="mc-about__table mc-navtable">
              <thead>
                <tr>
                  <th scope="col">{t('admin.navigation.colOrder')}</th>
                  <th scope="col">{t('admin.navigation.colEntry')}</th>
                  <th scope="col">{t('admin.navigation.colPlugin')}</th>
                  <th scope="col">{t('admin.navigation.colVisibleTo')}</th>
                  <th scope="col">{t('admin.plugins.enabled')}</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry, index) => (
                  <tr
                    key={`${entry.pluginId}:${entry.path}`}
                    className="mc-navrow"
                    draggable
                    onDragStart={() => {
                      dragFrom.current = index;
                    }}
                    // Without preventDefault the browser refuses the drop — the default for most elements
                    // is "not a drop target".
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault();
                      if (dragFrom.current != null) {
                        move(dragFrom.current, index);
                        dragFrom.current = null;
                      }
                    }}
                  >
                    <td className="mc-navrow__move">
                      <button
                        type="button"
                        className="mc-btn mc-btn--icon mc-move mc-move--up"
                        disabled={index === 0}
                        aria-label={t('admin.navigation.moveUp', { label: entry.label })}
                        onClick={() => move(index, index - 1)}
                      />
                      <button
                        type="button"
                        className="mc-btn mc-btn--icon mc-move mc-move--down"
                        disabled={index === entries.length - 1}
                        aria-label={t('admin.navigation.moveDown', { label: entry.label })}
                        onClick={() => move(index, index + 1)}
                      />
                    </td>
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
                      <label className="mc-toggle">
                        <input
                          type="checkbox"
                          checked={entry.enabled}
                          onChange={(e) => setEnabled(index, e.target.checked)}
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

          {/* A reorder is silent to anyone not watching the rows move. */}
          <p className="mc-sr-only" aria-live="polite">
            {announcement}
          </p>

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
