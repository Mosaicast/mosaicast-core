// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';

import { api } from '../api/client';
import type { Meta, Role } from '../api/types';
import { useUser } from '../auth/UserContext';
import { useLegalEntries } from '../hooks/useLegalEntries';
import { availableLocales, localeName } from '../i18n';
import { useSite } from '../theme/SiteContext';
import { Dropdown } from './Dropdown';
import { SlotRegion } from './SlotRegion';

const DEV_ROLES: Role[] = ['admin', 'podcaster', 'fan'];

/**
 * The persistent top-bar chrome (ARCHITECTURE §6/§8): brand, nav, the anonymous language switcher (§12.7),
 * and auth — a **Log in** menu (Discord `oauth2Login`, plus dev-login under the dev profile) when anonymous,
 * or an **account menu** (avatar / name / role → Account, Admin, Log out) when signed in. Hosts the `top`
 * plugin slot region.
 */
export function TopBar() {
  const { t, i18n } = useTranslation();
  const { site, mode } = useSite();
  const { user, refresh, logout } = useUser();
  const legal = useLegalEntries();
  const navigate = useNavigate();
  const [devLogin, setDevLogin] = useState(false);

  useEffect(() => {
    api
      .get<Meta>('/api/meta')
      .then((meta) => setDevLogin(meta.devLoginEnabled))
      .catch(() => setDevLogin(false));
  }, []);

  const logo =
    mode === 'dark' && site?.branding.darkLogo ? site.branding.darkLogo : (site?.branding.logo ?? '/branding/logo');
  const name = site?.name ?? t('app.title');
  const isStaff = user?.role === 'admin' || user?.role === 'podcaster';

  const locales = availableLocales();
  const currentLocale = i18n.language.slice(0, 2);
  const changeLocale = (code: string) => {
    void i18n.changeLanguage(code);
    localStorage.setItem('mc.locale', code);
  };

  const discordLogin = () => {
    window.location.href = '/oauth2/authorization/discord';
  };
  const doDevLogin = async (role: Role) => {
    await api.post(`/api/auth/dev-login?role=${role}`);
    await refresh();
  };
  const doLogout = async () => {
    await logout();
    navigate('/');
  };

  return (
    <header className="mc-top" data-slot="top">
      <div className="mc-top__inner">
        <Link className="mc-brand" to="/">
          <img className="mc-brand__logo" src={logo} alt="" aria-hidden="true" />
          <span className="mc-brand__name">{name}</span>
        </Link>

        <nav className="mc-nav" aria-label={t('nav.primary')}>
          <Link to="/">{t('nav.home')}</Link>
        </nav>

        <div className="mc-top__actions">
          <SlotRegion name="top" />
          {locales.length > 1 && (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              ariaLabel={t('nav.switchLanguage')}
              trigger={<span className="mc-menu__label">{localeName(currentLocale)}</span>}
            >
              {locales.map((code) => (
                <button
                  key={code}
                  type="button"
                  role="menuitem"
                  aria-current={code === currentLocale}
                  onClick={() => changeLocale(code)}
                >
                  {localeName(code)}
                </button>
              ))}
            </Dropdown>
          )}

          {legal.length > 0 && (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              ariaLabel={t('nav.info')}
              trigger={<span className="mc-menu__icon" aria-hidden="true">ⓘ</span>}
            >
              {legal.map((entry) => (
                <Link key={entry.slug} role="menuitem" to={`/legal/${entry.slug}`}>
                  {entry.title}
                </Link>
              ))}
            </Dropdown>
          )}

          {user ? (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              trigger={
                <span className="mc-menu__label">
                  {user.avatarUrl && <img className="mc-avatar" src={user.avatarUrl} alt="" aria-hidden="true" />}
                  {user.displayName}
                </span>
              }
            >
              <span className="mc-menu__role mc-muted">{t(`role.${user.role}`)}</span>
              <Link role="menuitem" to="/account">
                {t('account.title')}
              </Link>
              {isStaff && (
                <Link role="menuitem" to="/admin">
                  {t('nav.admin')}
                </Link>
              )}
              <button type="button" role="menuitem" onClick={doLogout}>
                {t('nav.logout')}
              </button>
            </Dropdown>
          ) : (
            <Dropdown
              triggerClassName="mc-btn mc-btn--accent"
              trigger={<span className="mc-menu__label">{t('nav.login')}</span>}
            >
              <button type="button" role="menuitem" onClick={discordLogin}>
                {t('login.discord')}
              </button>
              {devLogin && (
                <>
                  <span className="mc-menu__role mc-muted">{t('login.dev')}</span>
                  {DEV_ROLES.map((role) => (
                    <button key={role} type="button" role="menuitem" onClick={() => doDevLogin(role)}>
                      {t(`role.${role}`)}
                    </button>
                  ))}
                </>
              )}
            </Dropdown>
          )}
        </div>
      </div>
    </header>
  );
}
