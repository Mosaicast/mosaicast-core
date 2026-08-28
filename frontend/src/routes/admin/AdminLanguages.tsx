// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { AdminLocale, AdminLocalesView } from '../../api/types';
import { loadLocales } from '../../i18n';

/**
 * Languages admin (ARCHITECTURE §12.7, ADMIN only): which languages the shell offers, which content may be
 * authored in, and which is the site default.
 *
 * The list is whatever the host found — catalogs shipped with the release plus anything in the drop-in
 * directory — so adding a language is copying a file in and ticking a box, not a rebuild.
 *
 * The two columns are deliberately independent. A language needs a catalog to be offered in the shell; it
 * needs nothing at all to be a language an imprint is written in. Collapsing them into one switch would make
 * "a Dutch imprint on an English-only site" unexpressible, and that is a thing operators actually want.
 */
export function AdminLanguages() {
  const { t } = useTranslation();

  const [view, setView] = useState<AdminLocalesView | null>(null);
  const [rows, setRows] = useState<AdminLocale[]>([]);
  const [defaultLocale, setDefaultLocale] = useState('en');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const apply = (payload: AdminLocalesView) => {
    setView(payload);
    setRows(payload.locales);
    setDefaultLocale(payload.locales.find((locale) => locale.isDefault)?.code ?? payload.sourceLocale);
  };

  useEffect(() => {
    api
      .get<AdminLocalesView>('/api/admin/i18n')
      .then(apply)
      .catch(() => setError(t('admin.languages.loadFailed')));
  }, [t]);

  const toggle = (code: string, field: 'uiEnabled' | 'contentEnabled') =>
    setRows((current) =>
      current.map((row) => (row.code === code ? { ...row, [field]: !row[field] } : row)),
    );

  const save = async () => {
    setSaved(false);
    setError(null);
    try {
      const payload = await api.put<AdminLocalesView>('/api/admin/i18n', {
        uiLocales: rows.filter((row) => row.uiEnabled).map((row) => row.code),
        contentLocales: rows.filter((row) => row.contentEnabled).map((row) => row.code),
        defaultLocale,
      });
      apply(payload);
      // The switcher, the legal editor's tabs and every plugin read the same list; re-fetch it so the change
      // is visible without a reload.
      await loadLocales();
      setSaved(true);
    } catch (problem) {
      setError(
        problem instanceof ApiError
          ? (problem.detail ?? problem.message)
          : t('admin.languages.saveFailed'),
      );
    }
  };

  if (!view) {
    return <p className="mc-muted">{error ?? t('common.loading')}</p>;
  }

  return (
    <div className="mc-form">
      <h2>{t('admin.languages.title')}</h2>
      <p className="mc-muted">{t('admin.languages.intro')}</p>

      <div className="mc-tablewrap">
        <table className="mc-about__table mc-langtable">
          <thead>
            <tr>
              <th scope="col">{t('admin.languages.colLanguage')}</th>
              <th scope="col">{t('admin.languages.colCatalog')}</th>
              <th scope="col">{t('admin.languages.colShell')}</th>
              <th scope="col">{t('admin.languages.colContent')}</th>
              <th scope="col">{t('admin.languages.colDefault')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const isSource = row.code === view.sourceLocale;
              return (
                <tr key={row.code}>
                  <th scope="row">
                    {row.nativeName} <code>{row.code}</code>
                  </th>
                  <td>
                    {row.origin === 'NONE' ? (
                      <span className="mc-muted">{t('admin.languages.noCatalog')}</span>
                    ) : (
                      <>
                        <span className="mc-chip">
                          {t(`admin.languages.origin.${row.origin === 'DROP_IN' ? 'dropIn' : 'bundled'}`)}
                        </span>{' '}
                        <span className="mc-muted">
                          {row.missingKeys > 0
                            ? t('admin.languages.missing', { count: row.missingKeys })
                            : t('admin.languages.complete', { count: row.keyCount })}
                        </span>
                      </>
                    )}
                  </td>
                  <td>
                    {/* No catalog means nothing to render with; English is the fallback everything else
                        resolves to, so it can never be switched off. */}
                    <input
                      type="checkbox"
                      aria-label={t('admin.languages.shellOf', { language: row.nativeName })}
                      checked={row.uiEnabled}
                      disabled={isSource || row.origin === 'NONE'}
                      onChange={() => toggle(row.code, 'uiEnabled')}
                    />
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={t('admin.languages.contentOf', { language: row.nativeName })}
                      checked={row.contentEnabled}
                      disabled={isSource || row.code === defaultLocale}
                      onChange={() => toggle(row.code, 'contentEnabled')}
                    />
                  </td>
                  <td>
                    <input
                      type="radio"
                      name="mc-default-locale"
                      aria-label={t('admin.languages.defaultOf', { language: row.nativeName })}
                      checked={defaultLocale === row.code}
                      disabled={!row.contentEnabled}
                      onChange={() => setDefaultLocale(row.code)}
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <p className="mc-muted">
        {view.dropInDir
          ? t('admin.languages.dropInHint', { dir: view.dropInDir })
          : t('admin.languages.noDropInDir')}
      </p>

      {error && <p className="mc-error">{error}</p>}

      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={save}>
          {t('common.save')}
        </button>
        {saved && <span className="mc-muted">{t('admin.languages.saved')}</span>}
      </div>
    </div>
  );
}
