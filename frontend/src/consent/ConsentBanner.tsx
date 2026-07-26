// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { useConsent } from './ConsentContext';

/**
 * The consent banner (ARCHITECTURE §12.5). Rendered **only** when an active plugin declares a non-necessary
 * category — a site running the core alone, or only plugins that touch no third parties, never sees it. Each
 * category is listed with the plugins that asked for it and the third-party hosts they declared, because the
 * notice is generated from those declarations rather than written by hand.
 */
export function ConsentBanner() {
  const { t } = useTranslation();
  const { categories, sources, privacySlug, decide, decided, settingsOpen } = useConsent();
  const [checked, setChecked] = useState<Record<string, boolean>>({});

  useEffect(() => {
    setChecked(Object.fromEntries(categories.map((c) => [c.category, false])));
  }, [categories]);

  if (categories.length === 0 || (decided && !settingsOpen)) {
    return null;
  }

  const label = (category: string, known: boolean) =>
    known ? t(`consent.category.${category}`) : category;

  return (
    <section className="mc-consent" role="dialog" aria-label={t('consent.title')}>
      <h2 className="mc-consent__title">{t('consent.title')}</h2>
      <p className="mc-muted">{t('consent.intro')}</p>

      <ul className="mc-consent__list">
        {categories.map((category) => (
          <li key={category.category}>
            <label className="mc-toggle">
              <input
                type="checkbox"
                checked={checked[category.category] ?? false}
                onChange={(e) =>
                  setChecked((current) => ({ ...current, [category.category]: e.target.checked }))
                }
              />
              {label(category.category, category.known)}
            </label>
            <span className="mc-muted"> {t('consent.requestedBy', { plugins: category.pluginIds.join(', ') })}</span>
          </li>
        ))}
      </ul>

      {sources.length > 0 && (
        <p className="mc-muted">
          {t('consent.sources', { sources: [...new Set(sources.map((s) => s.source))].join(', ') })}
        </p>
      )}

      <div className="mc-form__actions">
        <button
          type="button"
          className="mc-btn mc-btn--accent"
          onClick={() => decide(Object.fromEntries(categories.map((c) => [c.category, true])))}
        >
          {t('consent.acceptAll')}
        </button>
        <button
          type="button"
          className="mc-btn"
          onClick={() => decide(Object.fromEntries(categories.map((c) => [c.category, false])))}
        >
          {t('consent.rejectAll')}
        </button>
        <button type="button" className="mc-btn" onClick={() => decide(checked)}>
          {t('consent.saveSelection')}
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
