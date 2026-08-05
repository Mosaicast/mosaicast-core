// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { CookieSettings } from './CookieSettings';
import { useConsent } from './ConsentContext';

/**
 * The first layer of the consent ask (ARCHITECTURE §12.5), and the container the settings open in.
 *
 * Rendered **only** when an active plugin declares a service under a non-necessary category: a site running
 * the core alone, or only plugins that touch no third parties, never sees it. That is the point of the whole
 * arrangement — the core's own storage is necessary for the service the visitor asked for, so there is
 * nothing to ask about.
 *
 * Three rules shape what this says, and all three came out of the legal reading rather than taste:
 *
 * - **Concrete purposes on layer one.** Not "we use cookies", but which companies would be loaded and what
 *   they store. The names come from the declarations, so they cannot drift from what actually loads.
 * - **Refuse is as prominent as allow.** Same element, same weight, same row. A quieter reject button is a
 *   dark pattern with a case history (DSK guidance Nov 2024; OLG Köln 2025).
 * - **Nothing is pre-ticked**, and closing the banner grants nothing — there is no dismiss, because a
 *   dismiss that counts as consent is not consent.
 *
 * It never says *plugin*: a visitor is not being asked about the site's architecture.
 */
export function ConsentBanner() {
  const { t } = useTranslation();
  const { categories, privacySlug, decide, decided, settingsOpen, openSettings, closeSettings } = useConsent();

  if (categories.length === 0) {
    return settingsOpen ? <SettingsDialog onClose={closeSettings} /> : null;
  }
  if (settingsOpen) {
    return <SettingsDialog onClose={closeSettings} />;
  }
  if (decided) {
    return null;
  }

  const all = (on: boolean) => Object.fromEntries(categories.map((category) => [category.id, on]));
  const providers = categories
    .flatMap((category) => category.services.map((service) => service.provider ?? service.name))
    .filter((provider, index, list) => list.indexOf(provider) === index);

  return (
    <section className="mc-consent mc-consent--banner" role="dialog" aria-label={t('consent.title')}>
      <h2 className="mc-consent__title">{t('consent.title')}</h2>
      <p>{t('consent.intro')}</p>
      {/* The companies, named — this is what a visitor is actually deciding about. */}
      <p className="mc-muted">{t('consent.providers', { providers: providers.join(', ') })}</p>

      <div className="mc-consent__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => decide(all(true))}>
          {t('consent.acceptAll')}
        </button>
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => decide(all(false))}>
          {t('consent.rejectAll')}
        </button>
        <button type="button" className="mc-btn" onClick={openSettings}>
          {t('consent.customise')}
        </button>
        {privacySlug && (
          <Link className="mc-consent__link" to={`/legal/${privacySlug}`}>
            {t('consent.privacy')}
          </Link>
        )}
      </div>
    </section>
  );
}

/**
 * The settings, opened over the page. Also what a plugin's `ctx.consent.request(category)` surfaces — there
 * is one consent surface host-wide, so a second request while this is open joins it rather than stacking a
 * dialog of its own.
 */
function SettingsDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const sheet = useRef<HTMLDivElement>(null);

  // `aria-modal` is a claim, so it has to be true: focus moves into the sheet when it opens, and Escape
  // closes it. Dismissing grants nothing — the host resolves any pending `request()` with the state as it
  // stands rather than recording a decision the visitor did not make.
  useEffect(() => {
    sheet.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="mc-consent__overlay" role="dialog" aria-modal="true" aria-label={t('consent.settingsTitle')}>
      <div className="mc-consent__sheet" ref={sheet} tabIndex={-1}>
        <CookieSettings onClose={onClose} />
      </div>
    </div>
  );
}
