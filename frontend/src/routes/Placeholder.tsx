// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

/**
 * A named stub for routes whose real views land in later E4 phases (feed page + detail in E4b, account in
 * E4c). Keeps the router shell navigable and gives each route a stable landmark to test against now.
 */
export function Placeholder({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation();
  return (
    <section className="mc-page">
      <h1 className="mc-page__title">{t(titleKey)}</h1>
      <p className="mc-muted">{t('common.comingSoon')}</p>
    </section>
  );
}

/** Real HTTP-style 404 landmark for unknown routes (ARCHITECTURE §6.6 hygiene). */
export function NotFound() {
  const { t } = useTranslation();
  return (
    <section className="mc-page">
      <h1 className="mc-page__title">{t('notFound.title')}</h1>
      <p className="mc-muted">{t('notFound.body')}</p>
    </section>
  );
}
