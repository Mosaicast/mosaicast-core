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
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api
      .get<UserAdminView[]>('/api/admin/users')
      .then(setUsers)
      .catch(() => setError(t('admin.users.loadFailed')));
  };
  useEffect(load, [t]);

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
    </div>
  );
}
