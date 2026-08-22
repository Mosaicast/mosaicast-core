// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { Role, UserAdminView } from '../../api/types';
import { useUser } from '../../auth/UserContext';

const ROLES: Role[] = ['fan', 'podcaster', 'admin'];

/**
 * Admin user management (ARCHITECTURE §8.5, ADMIN only): list users and change their role. Granting ADMIN
 * asks for confirmation; the server rejects changing your own role and demoting the last admin, and those
 * errors are surfaced inline. Role changes take effect on the user's next request.
 */
export function AdminUsers() {
  const { t } = useTranslation();
  const { user: me } = useUser();
  const [users, setUsers] = useState<UserAdminView[]>([]);
  const [erasures, setErasures] = useState<ErasureView[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api
      .get<UserAdminView[]>('/api/admin/users')
      .then(setUsers)
      .catch(() => setError(t('admin.users.loadFailed')));
    // Empty is the normal state and renders nothing; a row here is an obligation the operator is carrying
    // without having been told (§12).
    api
      .get<ErasureView[]>('/api/admin/erasures')
      .then(setErasures)
      .catch(() => setErasures([]));
  };
  useEffect(load, [t]);

  const retryErasures = async () => {
    setError(null);
    try {
      setErasures(await api.post<ErasureView[]>('/api/admin/erasures/retry'));
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : t('admin.erasures.retryFailed'));
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
      <ul className="mc-userlist">
        {users.map((u) => {
          const isSelf = u.id === me?.id;
          const email = u.identities.find((i) => i.email)?.email;
          return (
            <li key={u.id} className="mc-userlist__row">
              <div className="mc-userlist__who">
                {u.avatarUrl && <img className="mc-avatar" src={u.avatarUrl} alt="" aria-hidden="true" />}
                <div>
                  <span className="mc-userlist__name">{u.displayName}</span>
                  <span className="mc-muted mc-userlist__meta">
                    {u.identities.map((i) => i.provider).join(', ') || t('admin.users.noIdentities')}
                    {email ? ` · ${email}` : ''}
                  </span>
                </div>
              </div>
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
            </li>
          );
        })}
      </ul>

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

/** One unfinished account erasure, as `/api/admin/erasures` reports it. */
interface ErasureView {
  id: string;
  userId: string;
  pluginId: string;
  status: 'PENDING' | 'FAILED' | 'DONE';
  attempts: number;
  lastError: string | null;
}
