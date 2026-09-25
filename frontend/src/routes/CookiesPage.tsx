// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { CookieSettings } from '../consent/CookieSettings';
import { useDocumentTitle } from '../a11y/documentTitle';

/**
 * `/cookies` — the settings at a stable address (ARCHITECTURE §12.5).
 *
 * It exists even on a site that never shows a banner, and that is the point: the core stores a session
 * cookie, a language and a playback position whether or not any plugin is installed, so there has to be one
 * place that says so and one switch that can be reached without a plugin having created it. The footer link
 * is unconditional for the same reason — before, a core-only install had no withdrawal surface at all.
 */
export function CookiesPage() {
  const { t } = useTranslation();
  useDocumentTitle(t('consent.settingsTitle'));
  return (
    <section className="mc-page">
      {/* The component brings its own heading, promoted to `h1` here — one page, one top-level heading. */}
      <CookieSettings titleAs="h1" />
    </section>
  );
}
