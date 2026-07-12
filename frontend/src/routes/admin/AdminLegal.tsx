// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { LegalAdminPage } from '../../api/types';

const LOCALES = ['en', 'de'];
const ROLE_MARKERS = ['', 'privacy', 'imprint', 'terms'];

/** Editor for one legal page: role marker + sort order, and a title/markdown body per locale (§12.6). */
function PageEditor({ page, onChanged }: { page: LegalAdminPage; onChanged: () => void }) {
  const { t } = useTranslation();
  const [roleMarker, setRoleMarker] = useState(page.roleMarker ?? '');
  const [sortOrder, setSortOrder] = useState(page.sortOrder);
  const [bodies, setBodies] = useState<Record<string, { title: string; markdown: string }>>(() => {
    const map: Record<string, { title: string; markdown: string }> = {};
    for (const locale of LOCALES) {
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

  return (
    <div className="mc-legalpage">
      <div className="mc-legalpage__head">
        <strong>{page.slug}</strong>
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

      {LOCALES.map((locale) => (
        <div key={locale} className="mc-legaltr">
          <div className="mc-legaltr__head">
            <span className="mc-legaltr__locale">{locale.toUpperCase()}</span>
            <input
              className="mc-input"
              type="text"
              placeholder={t('admin.legal.pageTitle')}
              value={bodies[locale].title}
              onChange={(e) => setBodies({ ...bodies, [locale]: { ...bodies[locale], title: e.target.value } })}
            />
            <button type="button" className="mc-btn" onClick={() => saveTranslation(locale)}>
              {t('common.save')}
            </button>
          </div>
          <textarea
            className="mc-textarea"
            rows={5}
            placeholder="# Markdown…"
            value={bodies[locale].markdown}
            onChange={(e) => setBodies({ ...bodies, [locale]: { ...bodies[locale], markdown: e.target.value } })}
          />
        </div>
      ))}
    </div>
  );
}

/** Legal-pages mini-CMS admin (ARCHITECTURE §12.6, ADMIN only): list, create, edit and delete pages. */
export function AdminLegal() {
  const { t } = useTranslation();
  const [pages, setPages] = useState<LegalAdminPage[]>([]);
  const [newSlug, setNewSlug] = useState('');

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

      {pages.map((page) => (
        <PageEditor key={page.slug} page={page} onChanged={load} />
      ))}
    </div>
  );
}
