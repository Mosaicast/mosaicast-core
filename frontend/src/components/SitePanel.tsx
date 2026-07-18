// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { useSite } from '../theme/SiteContext';
import { SlotRegion } from './SlotRegion';

/**
 * The site-scope panel (ARCHITECTURE §6.1) — the "All" tab counterpart to {@link FeedPanel}: the site logo
 * and name, plus the **site** plugin slot region for site-wide plugins (E5). Keeps the two-column layout
 * consistent when no single feed is selected.
 */
export function SitePanel() {
  const { t } = useTranslation();
  const { site, mode } = useSite();

  const logo =
    mode === 'dark' && site?.branding.darkLogo
      ? site.branding.darkLogo
      : (site?.branding.logo ?? '/branding/logo');
  const name = site?.name ?? t('app.title');

  return (
    <aside className="mc-scope-panel">
      <img className="mc-scope-panel__logo" src={logo} alt="" aria-hidden="true" />
      <h1 className="mc-scope-panel__title">{name}</h1>
      {/* Site-scoped plugins (E5) mount here — both the dedicated `site` region and a site-scoped sidebar. */}
      <SlotRegion name="site" />
      <SlotRegion name="sidebar" />
    </aside>
  );
}
