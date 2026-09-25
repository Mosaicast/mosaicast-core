// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink, Outlet, useLocation } from 'react-router-dom';

import { useUser } from '../../auth/UserContext';
import { useDocumentTitle } from '../../a11y/documentTitle';

/**
 * The admin area shell (ARCHITECTURE §8.5, §12): a role-gated side nav plus the routed section. Site and
 * legal editing are ADMIN-only; feed management is a PODCASTER capability. The server enforces access too.
 */
export function AdminLayout() {
  const { t, i18n } = useTranslation();
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';
  const nav = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  // "Feeds · Admin": the section, which is what differs between the tabs a visitor has open.
  const section = pathname.split('/')[2] ?? '';
  const sectionKey = `admin.nav.${section}`;
  useDocumentTitle(
    i18n.exists(sectionKey) ? `${t(sectionKey)} · ${t('admin.title')}` : t('admin.title'),
  );

  // On a phone the nav is a horizontal strip (admin.css), and the active section can start off-screen —
  // arriving on a page whose own tab is not visible reads as the wrong page. Scroll it into view, without
  // touching the vertical position: `block: 'nearest'` leaves the page where the router put it.
  useEffect(() => {
    nav.current
      ?.querySelector('.mc-adminnav__link--active')
      ?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }, [pathname]);

  // Which ends of the phone strip hide more tabs. It was hard-cut mid-word with nothing saying that Users,
  // Plugins, Navigation and the rest were still there to scroll to (#200); the edge that has more fades.
  const [more, setMore] = useState({ start: false, end: false });
  useEffect(() => {
    const strip = nav.current;
    if (!strip) {
      return;
    }
    // From where the first and last tabs actually are, not from `scrollLeft`: the strip snaps its first tab
    // past its own padding, so a scroll offset of 16px is "at the start", and a fade there covered the
    // active tab.
    const measure = () => {
      const links = strip.querySelectorAll('.mc-adminnav__link');
      const first = links[0]?.getBoundingClientRect();
      const last = links[links.length - 1]?.getBoundingClientRect();
      const box = strip.getBoundingClientRect();
      setMore({
        start: first != null && first.left < box.left - 1,
        end: last != null && last.right > box.right + 1,
      });
    };
    measure();
    strip.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('resize', measure);
    return () => {
      strip.removeEventListener('scroll', measure);
      window.removeEventListener('resize', measure);
    };
  }, [pathname]);

  const tab = ({ isActive }: { isActive: boolean }) => `mc-adminnav__link${isActive ? ' mc-adminnav__link--active' : ''}`;

  return (
    <section className="mc-page mc-admin">
      <h1 className="mc-page__title">{t('admin.title')}</h1>
      <div className="mc-admin__body">
        <nav
          className={`mc-adminnav${more.start ? ' mc-adminnav--more-start' : ''}${more.end ? ' mc-adminnav--more-end' : ''}`}
          aria-label={t('admin.title')}
          ref={nav}
        >
          {/*
            First, because `/admin` lands here: the section a visit to the admin area opens on should be
            the section the nav reads as current, and it is also the only entry a podcaster sees at all.
          */}
          <NavLink to="/admin/feeds" className={tab}>
            {t('admin.nav.feeds')}
          </NavLink>
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
          {/* For a podcaster too: the route admits them for the fields a manifest delegates to them, and
              nothing linked to it, so the page existed only for someone who knew the URL (#192). */}
          <NavLink to="/admin/plugins" className={tab}>
            {t('admin.nav.plugins')}
          </NavLink>
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
