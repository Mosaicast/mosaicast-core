// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { LegalAdminPage } from '../../api/types';
import { availableLocales, localeName } from '../../i18n';

const ROLE_MARKERS = ['', 'privacy', 'imprint', 'terms'];

/**
 * Editor for one legal page: role marker + sort order, and a **tabbed** title/markdown body per available UI
 * language (§12.6). Tabs come from the registered i18n locales, so a new language needs no change here.
 */
function PageEditor({ page, onChanged }: { page: LegalAdminPage; onChanged: () => void }) {
  const { t } = useTranslation();
  const locales = availableLocales();
  const [roleMarker, setRoleMarker] = useState(page.roleMarker ?? '');
  const [sortOrder, setSortOrder] = useState(page.sortOrder);
  const [activeLocale, setActiveLocale] = useState(locales[0] ?? 'en');
  const [bodies, setBodies] = useState<Record<string, { title: string; markdown: string }>>(() => {
    const map: Record<string, { title: string; markdown: string }> = {};
    for (const locale of locales) {
      const tr = page.translations.find((x) => x.locale === locale);
      map[locale] = { title: tr?.title ?? '', markdown: tr?.markdown ?? '' };
    }
    return map;
  });

  const saveMeta = async () => {
    await api.put(`/api/admin/legal/${page.slug}`, { slug: page.slug, roleMarker: roleMarker || null, sortOrder });
    onChanged();
  };
  const saveTranslation = async (locale: string) => {
    await api.put(`/api/admin/legal/${page.slug}/translations/${locale}`, bodies[locale]);
    onChanged();
  };
  const deletePage = async () => {
    await api.del(`/api/admin/legal/${page.slug}`);
    onChanged();
  };

  const body = bodies[activeLocale] ?? { title: '', markdown: '' };
  const hasTranslation = (locale: string) => page.translations.some((x) => x.locale === locale);

  return (
    <div className="mc-legalpage">
      <div className="mc-legalpage__head">
        <label className="mc-field mc-field--inline">
          <span>{t('admin.legal.role')}</span>
          <select value={roleMarker} onChange={(e) => setRoleMarker(e.target.value)}>
            {ROLE_MARKERS.map((r) => (
              <option key={r} value={r}>
                {r || t('admin.legal.roleNone')}
              </option>
            ))}
          </select>
        </label>
        <label className="mc-field mc-field--inline">
          <span>{t('admin.legal.order')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            value={sortOrder}
            onChange={(e) => setSortOrder(Number(e.target.value))}
          />
        </label>
        <button type="button" className="mc-btn" onClick={saveMeta}>
          {t('common.save')}
        </button>
        <button type="button" className="mc-btn" onClick={deletePage}>
          {t('common.delete')}
        </button>
      </div>

      <div className="mc-tabs mc-tabs--sub" role="tablist">
        {locales.map((locale) => (
          <button
            key={locale}
            type="button"
            role="tab"
            aria-selected={locale === activeLocale}
            className={`mc-tab${locale === activeLocale ? ' mc-tab--active' : ''}`}
            onClick={() => setActiveLocale(locale)}
          >
            {localeName(locale)}
            {!hasTranslation(locale) && <span className="mc-tab__empty"> ·</span>}
          </button>
        ))}
      </div>

      <div className="mc-legaltr">
        <input
          className="mc-input"
          type="text"
          placeholder={t('admin.legal.pageTitle')}
          value={body.title}
          onChange={(e) => setBodies({ ...bodies, [activeLocale]: { ...body, title: e.target.value } })}
        />
        <textarea
          className="mc-textarea"
          rows={10}
          placeholder="# Markdown…"
          value={body.markdown}
          onChange={(e) => setBodies({ ...bodies, [activeLocale]: { ...body, markdown: e.target.value } })}
        />
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => saveTranslation(activeLocale)}>
          {t('admin.legal.saveLang', { lang: localeName(activeLocale) })}
        </button>
      </div>
    </div>
  );
}

/** Legal-pages mini-CMS admin (ARCHITECTURE §12.6, ADMIN only): a page list; each opens a tabbed editor. */
export function AdminLegal() {
  const { t } = useTranslation();
  const [pages, setPages] = useState<LegalAdminPage[]>([]);
  const [newSlug, setNewSlug] = useState('');
  const [editing, setEditing] = useState<string | null>(null);

  const load = () => api.get<LegalAdminPage[]>('/api/admin/legal').then(setPages).catch(() => {});
  useEffect(() => {
    void load();
  }, []);

  const createPage = async () => {
    await api.post('/api/admin/legal', { slug: newSlug.trim(), roleMarker: null, sortOrder: pages.length });
    setNewSlug('');
    await load();
  };

  return (
    <div className="mc-form">
      <h2>{t('admin.legal.title')}</h2>
      <div className="mc-feedadd">
        <input
          className="mc-input"
          type="text"
          placeholder={t('admin.legal.newSlug')}
          value={newSlug}
          onChange={(e) => setNewSlug(e.target.value)}
        />
        <button type="button" className="mc-btn mc-btn--accent" disabled={!newSlug.trim()} onClick={createPage}>
          {t('admin.legal.create')}
        </button>
      </div>

      <ul className="mc-list">
        {pages.map((page) => (
          <li key={page.slug} className="mc-legalrow">
            <div className="mc-legalrow__head">
              <div>
                <strong>{page.slug}</strong>
                <span className="mc-muted mc-legalrow__meta">
                  {page.roleMarker ?? t('admin.legal.roleNone')} · #{page.sortOrder} ·{' '}
                  {page.translations.map((x) => x.locale.toUpperCase()).join(', ') || t('admin.legal.noLangs')}
                </span>
              </div>
              <button
                type="button"
                className="mc-btn"
                onClick={() => setEditing(editing === page.slug ? null : page.slug)}
              >
                {editing === page.slug ? t('common.close') : t('common.edit')}
              </button>
            </div>
            {editing === page.slug && (
              <PageEditor
                page={page}
                onChanged={() => {
                  void load();
                }}
              />
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
