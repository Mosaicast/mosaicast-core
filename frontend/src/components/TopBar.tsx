// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { useSite } from '../theme/SiteContext';
import { SlotRegion } from './SlotRegion';

/**
 * The persistent top-bar chrome (ARCHITECTURE §6, mockup `data-slot="top"`): brand (logo from the site
 * payload, swapped for the dark logo in dark mode), primary nav, the anonymous language switcher (§12.7),
 * and the login entry (wired to real auth in E4c). Hosts the `top` plugin slot region.
 */
export function TopBar() {
  const { t, i18n } = useTranslation();
  const { site, mode } = useSite();

  const logo =
    mode === 'dark' && site?.branding.darkLogo ? site.branding.darkLogo : (site?.branding.logo ?? '/branding/logo');
  const name = site?.name ?? t('app.title');

  const toggleLocale = () => {
    const next = i18n.language.startsWith('de') ? 'en' : 'de';
    void i18n.changeLanguage(next);
    localStorage.setItem('mc.locale', next);
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
          <button
            type="button"
            className="mc-btn mc-btn--ghost"
            onClick={toggleLocale}
            aria-label={t('nav.switchLanguage')}
          >
            {i18n.language.startsWith('de') ? 'EN' : 'DE'}
          </button>
          {/* Real login/account menu lands in E4c; this is the entry affordance. */}
          <Link className="mc-btn mc-btn--accent" to="/account">
            {t('nav.login')}
          </Link>
        </div>
      </div>
    </header>
  );
}
