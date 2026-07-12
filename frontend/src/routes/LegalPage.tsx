// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { ApiError, api } from '../api/client';
import type { RenderedPage } from '../api/types';

/**
 * A public legal page (`GET /api/legal/{slug}`), e.g. `/legal/privacy` or `/legal/imprint`. The server
 * returns already-sanitized HTML (markdown → safe HTML), so it is injected directly. Locale-aware with the
 * server's fallback to the default locale.
 */
export function LegalPage() {
  const { slug } = useParams<{ slug: string }>();
  const { t, i18n } = useTranslation();
  const locale = i18n.language.slice(0, 2);
  const [page, setPage] = useState<RenderedPage | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let active = true;
    setPage(null);
    setMissing(false);
    api
      .get<RenderedPage>(`/api/legal/${encodeURIComponent(slug ?? '')}?locale=${encodeURIComponent(locale)}`)
      .then((p) => active && setPage(p))
      .catch((e) => {
        if (active) {
          setMissing(e instanceof ApiError && e.status === 404);
        }
      });
    return () => {
      active = false;
    };
  }, [slug, locale]);

  if (missing) {
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
    </article>
  );
}
