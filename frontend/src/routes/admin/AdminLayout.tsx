// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { NavLink, Outlet } from 'react-router-dom';

import { useUser } from '../../auth/UserContext';

/**
 * The admin area shell (ARCHITECTURE §8.5, §12): a role-gated side nav plus the routed section. Site and
 * legal editing are ADMIN-only; feed management is a PODCASTER capability. The server enforces access too.
 */
export function AdminLayout() {
  const { t } = useTranslation();
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';

  const tab = ({ isActive }: { isActive: boolean }) => `mc-adminnav__link${isActive ? ' mc-adminnav__link--active' : ''}`;

  return (
    <section className="mc-page mc-admin">
      <h1 className="mc-page__title">{t('admin.title')}</h1>
      <div className="mc-admin__body">
        <nav className="mc-adminnav" aria-label={t('admin.title')}>
          {isAdmin && (
            <NavLink to="/admin/site" className={tab}>
              {t('admin.nav.site')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/legal" className={tab}>
              {t('admin.nav.legal')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/users" className={tab}>
              {t('admin.nav.users')}
            </NavLink>
          )}
          <NavLink to="/admin/feeds" className={tab}>
            {t('admin.nav.feeds')}
          </NavLink>
          {isAdmin && (
            <NavLink to="/admin/plugins" className={tab}>
              {t('admin.nav.plugins')}
            </NavLink>
          )}
        </nav>
        <div className="mc-admin__main">
          <Outlet />
        </div>
      </div>
    </section>
  );
}
