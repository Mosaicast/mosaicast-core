// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import type { ConsentCategory, ConsentServiceView } from '../api/types';
import { formatDate } from '../util/format';
import { useConsent } from './ConsentContext';
import { ProgressPreference } from './ProgressPreference';

/**
 * The consent settings, in one component (ARCHITECTURE §12.5).
 *
 * There is exactly one of these, and it appears in three places: the `/cookies` page, appended below the
 * legal page marked `privacy`, and inside the banner when a visitor chooses to decide in detail (or when a
 * plugin calls `ctx.consent.request()`). One component because withdrawal has to be **as easy as granting**,
 * and three near-identical surfaces are how that stops being true.
 *
 * Appending rather than embedding is forced by §12.6: legal pages are admin-written markdown, sanitised with
 * jsoup's relaxed safelist, which strips anything interactive. An admin cannot put a working switch in their
 * privacy text, so the shell puts it underneath.
 *
 * **The wording rules are not decoration.** Nothing here says *plugin*, *slot* or *manifest*; a visitor is
 * told about services, the companies that operate them, and what those store on their device. Allow and
 * refuse are rendered identically, because a reject button that is quieter than accept is not a choice
 * (DSK guidance, Nov 2024; OLG Köln 2025).
 */
export function CookieSettings({
  onClose,
  titleAs: Title = 'h2',
}: {
  onClose?: () => void;
  /** `h1` when this component *is* the page (`/cookies`), so the page has exactly one top-level heading. */
  titleAs?: 'h1' | 'h2';
}) {
  const { t, i18n } = useTranslation();
  const consent = useConsent();
  const { categories, essential, privacySlug, record, gpc, decide, has } = consent;
  const [choices, setChoices] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setChoices(Object.fromEntries(categories.map((category) => [category.id, has(category.id)])));
  }, [categories, has]);

  const all = (on: boolean) =>
    Object.fromEntries(categories.map((category) => [category.id, on]));

  const copyReceipt = async () => {
    if (!record) {
      return;
    }
    try {
      await navigator.clipboard.writeText(JSON.stringify(record, null, 2));
      setCopied(true);
    } catch {
      // Clipboard access can be refused; the receipt is on screen either way.
    }
  };

  return (
    <div className="mc-consent">
      <Title className="mc-consent__title">{t('consent.settingsTitle')}</Title>
      <p className="mc-muted">{t('consent.intro')}</p>

      {gpc && <p className="mc-consent__signal">{t('consent.gpc')}</p>}

      {categories.length === 0 ? (
        <p className="mc-muted">{t('consent.nothingOptional')}</p>
      ) : (
        <ul className="mc-consent__list">
          {categories.map((category) => (
            <CategoryBlock
              key={category.id}
              category={category}
              checked={choices[category.id] ?? false}
              onChange={(on) => setChoices((current) => ({ ...current, [category.id]: on }))}
            />
          ))}
        </ul>
      )}

      <section className="mc-consent__essential">
        <h3 className="mc-consent__subtitle">{t('consent.essential.title')}</h3>
        <p className="mc-muted">{t('consent.essential.intro')}</p>
        <StorageTable
          rows={essential.storage.map((item) => ({
            name: item.name,
            type: t(`consent.type.${item.type}`, { defaultValue: item.type }),
            purpose: t(item.purposeKey),
            duration: t(item.durationKey),
          }))}
        />
      </section>

      <ProgressPreference />

      <section className="mc-consent__receipt">
        <h3 className="mc-consent__subtitle">{t('consent.receipt.title')}</h3>
        {record ? (
          <>
            <p className="mc-muted">
              {t('consent.receipt.decidedAt', { date: formatDate(record.decidedAt, i18n.language) })}
            </p>
            <ul className="mc-consent__answers">
              {Object.entries(record.categories).map(([id, on]) => (
                <li key={id}>
                  <span>{label(t, id, categories)}</span>
                  <span className={on ? 'mc-consent__yes' : 'mc-muted'}>
                    {on ? t('consent.allowed') : t('consent.notAllowed')}
                  </span>
                </li>
              ))}
            </ul>
            <button type="button" className="mc-btn mc-btn--ghost" onClick={copyReceipt}>
              {copied ? t('consent.receipt.copied') : t('consent.receipt.copy')}
            </button>
          </>
        ) : (
          <p className="mc-muted">{t('consent.receipt.none')}</p>
        )}
      </section>

      <div className="mc-consent__actions">
        {/* Allow and refuse are the same element with the same weight — see the class note. */}
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => decide(all(true))}>
          {t('consent.acceptAll')}
        </button>
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => decide(all(false))}>
          {t('consent.rejectAll')}
        </button>
        {categories.length > 0 && (
          <button type="button" className="mc-btn" onClick={() => decide(choices)}>
            {t('consent.save')}
          </button>
        )}
        {privacySlug && (
          <Link className="mc-consent__link" to={`/legal/${privacySlug}`}>
            {t('consent.privacy')}
          </Link>
        )}
        {onClose && (
          <button type="button" className="mc-btn mc-btn--ghost" onClick={onClose}>
            {t('consent.close')}
          </button>
        )}
      </div>
    </div>
  );
}

/** One decision, with every service it covers named underneath it. */
function CategoryBlock({
  category,
  checked,
  onChange,
}: {
  category: ConsentCategory;
  checked: boolean;
  onChange: (on: boolean) => void;
}) {
  const { t } = useTranslation();
  return (
    <li className="mc-consent__category">
      <label className="mc-toggle mc-consent__switch">
        <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
        <span className="mc-consent__label">{label(t, category.id, [category])}</span>
      </label>
      {category.known && (
        <p className="mc-muted mc-consent__hint">{t(`consent.categoryHint.${category.id}`)}</p>
      )}
      {category.services.map((service) => (
        <ServiceBlock key={service.name} service={service} />
      ))}
    </li>
  );
}

/** One named service: who runs it, where their policy is, and what it puts on the device. */
function ServiceBlock({ service }: { service: ConsentServiceView }) {
  const { t } = useTranslation();
  return (
    <div className="mc-consent__service">
      <p className="mc-consent__servicename">
        {service.name}
        {service.provider && (
          <span className="mc-muted"> — {t('consent.provider', { provider: service.provider })}</span>
        )}
      </p>
      {service.thirdCountryTransfer && (
        <p className="mc-consent__warn">{t('consent.thirdCountry')}</p>
      )}
      {service.storage.length > 0 ? (
        <StorageTable
          rows={service.storage.map((item) => ({
            name: item.name,
            type: t(`consent.type.${item.type}`, { defaultValue: item.type }),
            purpose: item.purpose,
            duration: item.duration,
          }))}
        />
      ) : (
        <p className="mc-muted mc-consent__hint">{t('consent.storesNothing')}</p>
      )}
      {service.privacyUrl && (
        <a href={service.privacyUrl} target="_blank" rel="noreferrer noopener">
          {t('consent.privacyOf', { name: service.name })}
        </a>
      )}
    </div>
  );
}

/** What is stored, named exactly as it appears on the device — the disclosure §25 TDDDG asks for. */
function StorageTable({
  rows,
}: {
  rows: { name: string; type: string; purpose: string; duration: string }[];
}) {
  const { t } = useTranslation();
  if (rows.length === 0) {
    return null;
  }
  return (
    <table className="mc-consent__storage">
      <thead>
        <tr>
          <th>{t('consent.storage.name')}</th>
          <th>{t('consent.storage.type')}</th>
          <th>{t('consent.storage.purpose')}</th>
          <th>{t('consent.storage.duration')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.type}:${row.name}`}>
            <td><code>{row.name}</code></td>
            <td>{row.type}</td>
            <td>{row.purpose}</td>
            <td>{row.duration}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * A category's label. A plugin may declare its own category, which the shell has no translation for — it
 * shows the declared name rather than inventing one, and never a slug where a label was expected.
 */
function label(t: (key: string) => string, id: string, categories: ConsentCategory[]): string {
  const known = categories.find((category) => category.id === id)?.known;
  return known ? t(`consent.category.${id}`) : id;
}
