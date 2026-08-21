// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';

import { api } from '../api/client';
import { useMeta } from '../api/MetaContext';
import type { Role } from '../api/types';
import { useUser } from '../auth/UserContext';
import { useLegalEntries } from '../hooks/useLegalEntries';
import { availableLocales, localeName } from '../i18n';
import { useSite } from '../theme/SiteContext';
import { Dropdown } from './Dropdown';
import { Icon } from './Icon';
import { SlotRegion } from './SlotRegion';

const DEV_ROLES: Role[] = ['admin', 'podcaster', 'fan'];

/**
 * The persistent top-bar chrome (ARCHITECTURE §6/§8): brand (which is the link home), the anonymous
 * language switcher (§12.7),
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
  const devLogin = useMeta()?.devLoginEnabled ?? false;

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
        {/*
          The brand is the way home — one affordance, the one every site trains people to expect. It used
          to sit beside a nav whose only item was a second link to the same place, which cost a row of
          header on a phone to say the same thing twice.
        */}
        <Link className="mc-brand" to="/">
          <img className="mc-brand__logo" src={logo} alt="" aria-hidden="true" />
          <span className="mc-brand__name">{name}</span>
        </Link>

        <div className="mc-top__actions">
          <SlotRegion name="top" />
          {locales.length > 1 && (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              ariaLabel={t('nav.switchLanguage')}
              trigger={
                <span className="mc-menu__label">
                  {/* Both labels ship; CSS picks one. A phone header has room for "EN", not "English",
                      and swapping in CSS keeps it a pure layout decision rather than a JS breakpoint. */}
                  <span className="mc-menu__long">{localeName(currentLocale)}</span>
                  <span className="mc-menu__short">{currentLocale.toUpperCase()}</span>
                </span>
              }
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

          {/*
            Unconditional. The menu used to appear only once a legal page existed, so a fresh install —
            the one where "what is this site?" is hardest to answer — had no info menu at all. /about
            ships with the shell and is always there, so there is always something to open.
          */}
          <Dropdown
            triggerClassName="mc-btn mc-btn--ghost"
            ariaLabel={t('nav.info')}
            trigger={
              <span className="mc-menu__icon">
                <Icon name="info" />
              </span>
            }
          >
            <Link role="menuitem" to="/about">
              {t('about.heading')}
            </Link>
            {legal.map((entry) => (
              <Link key={entry.slug} role="menuitem" to={`/legal/${entry.slug}`}>
                {entry.title}
              </Link>
            ))}
          </Dropdown>

          {user ? (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              trigger={
                <span className="mc-menu__label">
                  {user.avatarUrl && <img className="mc-avatar" src={user.avatarUrl} alt="" aria-hidden="true" />}
                  {/* Wrapped so the name alone can be truncated: clamping the label would take the
                      dropdown caret with it and leave the control looking like plain text. */}
                  <span className="mc-menu__name">{user.displayName}</span>
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
