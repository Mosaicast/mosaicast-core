// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import type { FooterEntry } from '../api/types';
import { useResource } from './useResource';

/**
 * The legal footer links (`GET /api/legal`), ordered by the admin's sort order and resolved to the active
 * locale (re-fetched on a language switch). Shared by the footer and the top-bar info menu. A failure
 * resolves to an empty list — legal links are best-effort chrome, and a page that renders without them is
 * better than one that does not render.
 */
export function useLegalEntries(): FooterEntry[] {
  const { i18n } = useTranslation();
  const locale = i18n.language.slice(0, 2);
  const { data } = useResource<FooterEntry[]>(`/api/legal?locale=${encodeURIComponent(locale)}`);
  return data ?? [];
}
