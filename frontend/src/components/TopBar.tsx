// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';

import { api } from '../api/client';
import { useMeta } from '../api/MetaContext';
import type { Role } from '../api/types';
import { useUser } from '../auth/UserContext';
import { Avatar } from './Avatar';
import { NotificationBell } from './NotificationBell';
import { useLegalEntries } from '../hooks/useLegalEntries';
import { availableLocales, ensureCatalog, localeName } from '../i18n';
import { useSite } from '../theme/SiteContext';
import { Dropdown } from './Dropdown';
import { Icon } from './Icon';
import { NavMenu } from './NavMenu';
import { SearchLink } from '../routes/SearchPage';
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
    // A drop-in language has no compiled-in catalog, so fetch it before switching — otherwise the first
    // render in that language is entirely fallback English and only corrects itself on the next keystroke.
    void ensureCatalog(code).then(() => i18n.changeLanguage(code));
    try {
      localStorage.setItem('mc.locale', code);
    } catch {
      // Storage blocked or full: the switch still applies to this page view, it just is not remembered.
    }
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
          The brand stays the way home — one affordance, the one every site trains people to expect. The nav
          that used to sit here was deleted because its only item was a second link to the same place; the
          menu below earns the space by carrying what nothing else does (other feeds from anywhere, and the
          plugin pages that were previously reachable only by typing a URL), and it hides itself when it
          would not.
        */}
        <NavMenu />
        <Link className="mc-brand" to="/">
          <img className="mc-brand__logo" src={logo} alt="" aria-hidden="true" />
          <span className="mc-brand__name">{name}</span>
        </Link>

        <div className="mc-top__actions">
          <SlotRegion name="top" />
          {/*
            A link rather than an inline field: the header is already crowded on a phone, and a search box
            that collapses into an icon is the same two taps with more moving parts. The page owns the
            input, focuses it, and keeps the query in the URL.
          */}
          <SearchLink />
          {locales.length > 1 && (
            <Dropdown
              /*
                Hidden on a phone, where the same choices ride inside the info menu below: brand +
                language + info + account did not fit a 375px header, and what overflowed was the
                account control at the right edge — clipped, not scrollable, so the login button sat
                half off the screen. One fewer control in the row is what buys the space back.
              */
              className="mc-top__lang"
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
            {/*
              The phone's language switcher. `display: contents` on a wide screen would put these in the
              menu twice, so the wrapper is `display: none` there and the standalone control above is the
              one that shows — the same one-of-two-renderings trick the language label already uses, kept
              in CSS so no JS breakpoint decides layout.
            */}
            {locales.length > 1 && (
              <div className="mc-menu__onphone">
                <div className="mc-menu__sep" role="separator" />
                <div className="mc-navmenu__heading" role="presentation">
                  {t('nav.switchLanguage')}
                </div>
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
              </div>
            )}
          </Dropdown>

          {/* Signed in only: an anonymous visitor has no inbox and no endpoint to ask (§17). */}
          {user && <NotificationBell />}

          {user ? (
            <Dropdown
              triggerClassName="mc-btn mc-btn--ghost"
              trigger={
                <span className="mc-menu__label">
                  <Avatar userId={user.id} />
                  {/* Wrapped so the name alone can give way: it truncates as the header narrows and
                      drops entirely on a phone, where the avatar and the caret already say what the
                      control is. Clamping the whole label instead would take the caret with it and
                      leave the control looking like plain text. */}
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
