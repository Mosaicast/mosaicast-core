// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { ApiError } from '../api/client';
import type { RenderedPage } from '../api/types';
import { CookieSettings } from '../consent/CookieSettings';
import { useResource } from '../hooks/useResource';
import { useDocumentTitle } from '../a11y/documentTitle';

/**
 * A public legal page (`GET /api/legal/{slug}`), e.g. `/legal/privacy` or `/legal/imprint`. The server
 * returns already-sanitized HTML (markdown → safe HTML), so it is injected directly. Locale-aware with the
 * server's fallback to the default locale.
 */
export function LegalPage() {
  const { slug } = useParams<{ slug: string }>();
  const { t, i18n } = useTranslation();
  const locale = i18n.language.slice(0, 2);
  const { data: page, error } = useResource<RenderedPage>(
    `/api/legal/${encodeURIComponent(slug ?? '')}?locale=${encodeURIComponent(locale)}`,
  );
  const missing = error instanceof ApiError && error.status === 404;
  useDocumentTitle(missing ? t('legal.notFound') : page?.title);

  if (error instanceof ApiError && error.status === 404) {
    return (
      <section className="mc-page">
        <h1 className="mc-page__title">{t('legal.notFound')}</h1>
      </section>
    );
  }
  if (!page) {
    return null;
  }
  return (
    <article className="mc-page mc-legal">
      <h1 className="mc-page__title">{page.title}</h1>
      {/* Server-sanitized HTML (markdown → safe HTML in LegalService). */}
      <div className="mc-legal__body" dangerouslySetInnerHTML={{ __html: page.html }} />
      {/*
        The privacy page is where a visitor goes looking for the switch, and the sanitiser (jsoup's relaxed
        safelist) strips anything interactive an admin might paste into their markdown. Appending the real
        settings is what keeps "withdraw as easily as you granted" true without asking admins to maintain a
        widget in prose (§12.5, §12.6).
      */}
      {page.role?.toLowerCase() === 'privacy' && <CookieSettings />}
    </article>
  );
}
