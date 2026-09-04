// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';


import { ApiError, api } from '../../api/client';
import type { Paged, Role, UserAdminView } from '../../api/types';
import { useUser } from '../../auth/UserContext';
import { Avatar } from '../../components/Avatar';
import { formatDate } from '../../util/format';

const ROLES: Role[] = ['fan', 'podcaster', 'admin'];

/**
 * Admin user management (ARCHITECTURE §8.5, ADMIN only): list users and change their role. Granting ADMIN
 * asks for confirmation; the server rejects changing your own role and demoting the last admin, and those
 * errors are surfaced inline. Role changes take effect on the user's next request.
 */
export function AdminUsers() {
  const { t, i18n } = useTranslation();
  const { user: me } = useUser();
  const [users, setUsers] = useState<UserAdminView[]>([]);
  const [erasures, setErasures] = useState<ErasureView[]>([]);
  const [error, setError] = useState<string | null>(null);
  /** The submitted search, not the keystroke — typing must not fire a request per character. */
  const [query, setQuery] = useState('');
  const [draftQuery, setDraftQuery] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  /** Which user's warning panel is open, and what has been sent to them (ARCHITECTURE §17). */
  const [warnFor, setWarnFor] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<WarningView[]>([]);
  const [warnText, setWarnText] = useState('');

  const load = () => {
    const params = new URLSearchParams({ page: String(page), size: '50' });
    if (query) {
      params.set('q', query);
    }
    api
      .get<Paged<UserAdminView>>(`/api/admin/users?${params}`)
      .then((paged) => {
        setUsers(paged.items);
        setTotalPages(Math.max(1, paged.totalPages));
      })
      .catch(() => setError(t('admin.users.loadFailed')));
    // Empty is the normal state and renders nothing; a row here is an obligation the operator is carrying
    // without having been told (§12).
    api
      .get<ErasureView[]>('/api/admin/erasures')
      .then(setErasures)
      .catch(() => setErasures([]));
  };
  useEffect(load, [t, query, page]);

  const retryErasures = async () => {
    setError(null);
    try {
      setErasures(await api.post<ErasureView[]>('/api/admin/erasures/retry'));
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : t('admin.erasures.retryFailed'));
    }
  };

  /**
   * Walks a user's display name back (ARCHITECTURE §8.6.1).
   *
   * There is deliberately no way to *set* a name here — the confirmation therefore cannot show what the
   * name will become, because the server decides that and an admin never gets to.
   */
  const revertName = async (u: UserAdminView) => {
    setError(null);
    if (!window.confirm(t('admin.users.confirmRevert', { name: u.displayName }))) {
      return;
    }
    try {
      await api.post(`/api/admin/users/${u.id}/name/revert`);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : t('admin.users.revertFailed'));
    }
  };

  /**
   * Opens (or closes) one user's warning panel.
   *
   * Fetched per user on demand rather than counted into the list: opening the admin page should not cost a
   * query per row for a surface most visits never look at.
   */
  const toggleWarnings = async (u: UserAdminView) => {
    if (warnFor === u.id) {
      setWarnFor(null);
      return;
    }
    setWarnFor(u.id);
    setWarnText('');
    setWarnings(await api.get<WarningView[]>(`/api/admin/users/${u.id}/warnings`).catch(() => []));
  };

  const sendWarning = async (u: UserAdminView) => {
    setError(null);
    try {
      await api.post(`/api/admin/users/${u.id}/warn`, { text: warnText.trim() });
      setWarnText('');
      setWarnings(await api.get<WarningView[]>(`/api/admin/users/${u.id}/warnings`));
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : t('admin.users.warnFailed'));
    }
  };

  const changeRole = async (u: UserAdminView, role: Role) => {
    setError(null);
    if (role === 'admin' && !window.confirm(t('admin.users.confirmAdmin', { name: u.displayName }))) {
      return;
    }
    try {
      await api.put(`/api/admin/users/${u.id}/role`, { role });
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.detail ?? e.message : t('admin.users.changeFailed'));
    }
  };

  return (
    <div className="mc-form">
      <h2>{t('admin.users.title')}</h2>
      {error && <p className="mc-error">{error}</p>}
      {/*
        Searching on the canonical key, so an account spelt with a Cyrillic character to imitate somebody
        is found by typing the name it is imitating — which is the search a moderator actually runs.
      */}
      <form
        className="mc-userlist__search"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setQuery(draftQuery.trim());
        }}
      >
        <input
          className="mc-input"
          type="search"
          value={draftQuery}
          placeholder={t('admin.users.searchPlaceholder')}
          aria-label={t('admin.users.search')}
          onChange={(e) => setDraftQuery(e.target.value)}
        />
        <button type="submit" className="mc-btn">
          {t('admin.users.search')}
        </button>
      </form>
      <ul className="mc-userlist">
        {users.map((u) => {
          const isSelf = u.id === me?.id;
          const email = u.identities.find((i) => i.email)?.email;
          return (
            <li key={u.id} className="mc-userlist__row">
              <div className="mc-userlist__who">
                <Avatar userId={u.id} />
                <div>
                  <span className="mc-userlist__name">{u.displayName}</span>
                  <span className="mc-muted mc-userlist__meta">
                    {u.identities.map((i) => i.provider).join(', ') || t('admin.users.noIdentities')}
                    {email ? ` · ${email}` : ''}
                  </span>
                </div>
              </div>
              <button
                type="button"
                className="mc-btn"
                title={t('admin.users.revertHelp')}
                onClick={() => revertName(u)}
              >
                {t('admin.users.revertName')}
              </button>
              <button
                type="button"
                className="mc-btn"
                aria-expanded={warnFor === u.id}
                onClick={() => toggleWarnings(u)}
              >
                {t('admin.users.warn')}
              </button>
              <label className="mc-userlist__role">
                <span className="mc-muted">{t('admin.users.role')}</span>
                <select
                  value={u.role}
                  disabled={isSelf}
                  title={isSelf ? t('admin.users.selfLocked') : undefined}
                  onChange={(e) => changeRole(u, e.target.value as Role)}
                >
                  {ROLES.map((r) => (
                    <option key={r} value={r}>
                      {t(`role.${r}`)}
                    </option>
                  ))}
                </select>
              </label>
              {warnFor === u.id && (
                <div className="mc-warnpanel">
                  <label className="mc-field">
                    <span>{t('admin.users.warnLabel')}</span>
                    <textarea
                      className="mc-textarea"
                      rows={3}
                      maxLength={500}
                      value={warnText}
                      onChange={(e) => setWarnText(e.target.value)}
                    />
                  </label>
                  <p className="mc-muted">{t('admin.users.warnHelp')}</p>
                  <button
                    type="button"
                    className="mc-btn mc-btn--accent"
                    disabled={!warnText.trim()}
                    onClick={() => sendWarning(u)}
                  >
                    {t('admin.users.warnSend')}
                  </button>
                  {warnings.length > 0 && (
                    <ul className="mc-list mc-warnpanel__sent">
                      {warnings.map((w) => (
                        <li key={w.id} className="mc-list__row">
                          <span>{w.text}</span>
                          {/*
                            The point of the panel. A warning exists so that somebody was told, and an
                            admin who cannot see whether it was opened is carrying that blind (§17).
                          */}
                          <span className="mc-muted">
                            {w.readAt
                              ? t('admin.users.warnRead', { when: formatDate(w.readAt, i18n.language) })
                              : t('admin.users.warnUnread')}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>

      {/* Only rendered when there is more than one page — a lone "1 of 1" is noise on a small install. */}
      {totalPages > 1 && (
        <div className="mc-logpanel__foot">
          <button type="button" className="mc-btn" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('admin.users.previous')}
          </button>
          <span className="mc-muted">{t('admin.users.pageOf', { page: page + 1, pages: totalPages })}</span>
          <button
            type="button"
            className="mc-btn"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('admin.users.next')}
          </button>
        </div>
      )}

      {/*
        Only when there is something to say. An erasure a plugin never finished is a legal obligation the
        operator is carrying blind — and the fix is usually one switch away (re-enable the plugin), so the
        row names the plugin and the reason rather than the person, whose account is already gone.
      */}
      {erasures.length > 0 && (
        <>
          <h2>{t('admin.erasures.title')}</h2>
          <p className="mc-muted">{t('admin.erasures.help')}</p>
          <ul className="mc-list">
            {erasures.map((e) => (
              <li key={e.id} className="mc-list__row">
                <span>{e.pluginId}</span>
                <span className="mc-muted">
                  {t(`admin.erasures.status.${e.status.toLowerCase()}`)}
                  {e.lastError ? ` · ${e.lastError}` : ''}
                  {e.attempts > 0 ? ` · ${t('admin.erasures.attempts', { count: e.attempts })}` : ''}
                </span>
              </li>
            ))}
          </ul>
          <button type="button" className="mc-btn" onClick={retryErasures}>
            {t('admin.erasures.retry')}
          </button>
        </>
      )}
    </div>
  );
}

/** One warning an admin sent, with whether it has been opened (ARCHITECTURE §17). */
interface WarningView {
  id: string;
  text: string;
  createdAt: string;
  readAt: string | null;
}

/** One unfinished account erasure, as `/api/admin/erasures` reports it. */
interface ErasureView {
  id: string;
  userId: string;
  pluginId: string;
  status: 'PENDING' | 'FAILED' | 'DONE';
  attempts: number;
  lastError: string | null;
}
