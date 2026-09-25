// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { AdminKindSection, LegalAdminPage, LegalDraft } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
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
  canPrefill,
}: {
  page: LegalAdminPage;
  onChanged: () => void;
  onError: (message: string | null) => void;
  /** Whether the site has a translation provider configured at all. */
  canPrefill: boolean;
}) {
  const { t } = useTranslation();
  const locales = contentLocales();
  const [roleMarker, setRoleMarker] = useState(page.roleMarker ?? '');
  const [sortOrder, setSortOrder] = useState(page.sortOrder);
  const [activeLocale, setActiveLocale] = useState(locales[0] ?? 'en');
  /** Which tab currently holds an unsaved machine translation. */
  const [drafted, setDrafted] = useState<string | null>(null);
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
  const [confirming, setConfirming] = useState(false);
  const deletePage = () => {
    setConfirming(false);
    void run(() => api.del(`/api/admin/legal/${page.slug}`));
  };

  /**
   * Fills the editor with a machine translation — into the form, never into the database.
   *
   * §12.6 ships the mechanism and no legal texts because a policy nobody read is false safety; writing a
   * machine translation straight through would be that with extra steps. So this is a draft the admin
   * reads, edits and saves themselves, and it is labelled as one until they do.
   */
  const runPrefill = () =>
    run(async () => {
      const draft = await api.post<LegalDraft>(
        `/api/admin/legal/${page.slug}/translations/${activeLocale}/prefill`,
        {},
      );
      setBodies({ ...bodies, [activeLocale]: { title: draft.title, markdown: draft.markdown } });
      setDrafted(activeLocale);
    });

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
                {r ? t(`admin.legal.roles.${r}`, { defaultValue: r }) : t('admin.legal.roleNone')}
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
        <button type="button" className="mc-btn" onClick={() => setConfirming(true)}>
          {t('common.delete')}
        </button>
      </div>

      {confirming && (
        <ConfirmDialog
          title={t('common.delete')}
          body={t('admin.legal.deleteConfirm', { slug: page.slug })}
          confirmLabel={t('common.delete')}
          // The slug: a legal page is a published URL, and typing the one being removed is the difference
          // between deleting the page you meant and the one above it in the list.
          confirmWord={page.slug}
          onConfirm={deletePage}
          onCancel={() => setConfirming(false)}
        />
      )}

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
        <div className="mc-form__actions">
          <button
            type="button"
            className="mc-btn mc-btn--accent"
            onClick={() => saveTranslation(activeLocale)}
          >
            {t('admin.legal.saveLang', { lang: localeName(activeLocale) })}
          </button>
          {canPrefill && (
            <button type="button" className="mc-btn" onClick={runPrefill}>
              {t('admin.legal.prefill', { lang: localeName(activeLocale) })}
            </button>
          )}
        </div>
        {drafted === activeLocale && (
          // Stays until they save or switch tabs: an admin who walked away mid-review should not come back
          // to something that looks like their own writing.
          <p className="mc-error">{t('admin.legal.draftWarning')}</p>
        )}
      </div>
    </div>
  );
}

/** Legal-pages mini-CMS admin (ARCHITECTURE §12.6, ADMIN only): a page list; each opens a tabbed editor. */
/** A page's title in the admin's language, else the first title it has in any. */
function pageTitle(page: LegalAdminPage, locale: string): string | null {
  const own = page.translations.find((x) => x.locale === locale)?.title?.trim();
  return own || page.translations.find((x) => x.title?.trim())?.title?.trim() || null;
}

export function AdminLegal() {
  const { t, i18n } = useTranslation();
  const [pages, setPages] = useState<LegalAdminPage[]>([]);
  const [newSlug, setNewSlug] = useState('');
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [canPrefill, setCanPrefill] = useState(false);

  // Asked once, not per page: whether the site has a translation provider is a property of the site.
  useEffect(() => {
    api
      .get<AdminKindSection[]>('/api/admin/external')
      .then((sections) =>
        setCanPrefill(sections.some((section) => section.kind === 'translation' && section.ready)),
      )
      // Absent-tolerant: no provider, or no permission to ask, simply means no button.
      .catch(() => setCanPrefill(false));
  }, []);

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
                {/* The page's own title where it has one, with the slug as its address beside it — the row
                    printed the slug twice and the title never, and "#10" was an unlabelled sort order (#192). */}
                <strong>{pageTitle(page, i18n.language.slice(0, 2)) ?? page.slug}</strong>
                <span className="mc-muted mc-legalrow__meta">
                  <code>/legal/{page.slug}</code> ·{' '}
                  {page.roleMarker
                    ? t(`admin.legal.roles.${page.roleMarker}`, { defaultValue: page.roleMarker })
                    : t('admin.legal.roleNone')}{' '}
                  ·{' '}
                  {t('admin.legal.position', { position: page.sortOrder })} ·{' '}
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
                canPrefill={canPrefill}
              />
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
