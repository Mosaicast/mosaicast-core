// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { LegalAdminPage } from '../../api/types';
import { contentLocales, localeName } from '../../i18n';

const ROLE_MARKERS = ['', 'privacy', 'imprint', 'terms'];

/** Prefers the server's problem+json detail (e.g. "slug 'privacy' already exists") over a generic message. */
function messageOf(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.detail ?? error.message ?? fallback;
  }
  return fallback;
}

/**
 * Editor for one legal page: role marker + sort order, and a **tabbed** title/markdown body per language
 * (§12.6). Tabs come from the site's *content* languages, not the shell's — an operator can require a Dutch
 * imprint without offering a Dutch UI, and the tab strip is the surface where that difference is visible.
 */
function PageEditor({
  page,
  onChanged,
  onError,
}: {
  page: LegalAdminPage;
  onChanged: () => void;
  onError: (message: string | null) => void;
}) {
  const { t } = useTranslation();
  const locales = contentLocales();
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

  // Every write goes through here: a rejected request has to say so, never fail silently.
  const run = async (action: () => Promise<unknown>) => {
    onError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      onError(messageOf(e, t('admin.legal.saveFailed')));
    }
  };

  const saveMeta = () =>
    run(() =>
      api.put(`/api/admin/legal/${page.slug}`, {
        slug: page.slug,
        roleMarker: roleMarker || null,
        sortOrder,
      }),
    );
  const saveTranslation = (locale: string) =>
    run(() => api.put(`/api/admin/legal/${page.slug}/translations/${locale}`, bodies[locale]));
  const deletePage = () => {
    if (!window.confirm(t('admin.legal.deleteConfirm', { slug: page.slug }))) {
      return;
    }
    void run(() => api.del(`/api/admin/legal/${page.slug}`));
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
  const [error, setError] = useState<string | null>(null);

  const load = () =>
    api
      .get<LegalAdminPage[]>('/api/admin/legal')
      .then(setPages)
      .catch((e) => setError(messageOf(e, t('admin.legal.loadFailed'))));
  useEffect(() => {
    void load();
    // `t` is stable for a given language and reloading on a language switch would be pointless work.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const createPage = async () => {
    setError(null);
    try {
      await api.post('/api/admin/legal', {
        slug: newSlug.trim(),
        roleMarker: null,
        sortOrder: pages.length,
      });
      setNewSlug('');
      await load();
    } catch (e) {
      // The common case is a duplicate slug (409). Before this, the rejection was thrown into the void and
      // the button looked dead.
      setError(messageOf(e, t('admin.legal.createFailed')));
    }
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
      {error && <p className="mc-error">{error}</p>}

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
                onError={setError}
              />
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
