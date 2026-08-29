// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink, Outlet, useLocation } from 'react-router-dom';

import { useUser } from '../../auth/UserContext';

/**
 * The admin area shell (ARCHITECTURE §8.5, §12): a role-gated side nav plus the routed section. Site and
 * legal editing are ADMIN-only; feed management is a PODCASTER capability. The server enforces access too.
 */
export function AdminLayout() {
  const { t } = useTranslation();
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';
  const nav = useRef<HTMLElement>(null);
  const { pathname } = useLocation();

  // On a phone the nav is a horizontal strip (admin.css), and the active section can start off-screen —
  // arriving on a page whose own tab is not visible reads as the wrong page. Scroll it into view, without
  // touching the vertical position: `block: 'nearest'` leaves the page where the router put it.
  useEffect(() => {
    nav.current
      ?.querySelector('.mc-adminnav__link--active')
      ?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, [pathname]);

  const tab = ({ isActive }: { isActive: boolean }) => `mc-adminnav__link${isActive ? ' mc-adminnav__link--active' : ''}`;

  return (
    <section className="mc-page mc-admin">
      <h1 className="mc-page__title">{t('admin.title')}</h1>
      <div className="mc-admin__body">
        <nav className="mc-adminnav" aria-label={t('admin.title')} ref={nav}>
          {isAdmin && (
            <NavLink to="/admin/site" className={tab}>
              {t('admin.nav.site')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/languages" className={tab}>
              {t('admin.nav.languages')}
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
          {isAdmin && (
            <NavLink to="/admin/navigation" className={tab}>
              {t('admin.nav.navigation')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/consent" className={tab}>
              {t('admin.nav.consent')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/external" className={tab}>
              {t('admin.nav.external')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/external" className={tab}>
              {t('admin.nav.external')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/seo" className={tab}>
              {t('admin.nav.seo')}
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin/logs" className={tab}>
              {t('admin.nav.logs')}
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
