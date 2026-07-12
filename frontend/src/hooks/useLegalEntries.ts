// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import type { FooterEntry } from '../api/types';

/**
 * The legal footer links (`GET /api/legal`), ordered by the admin's sort order and resolved to the active
 * locale (re-fetched on a language switch). Shared by the footer and the top-bar info menu so the list is
 * fetched once per surface. Failures resolve to an empty list — legal links are best-effort chrome.
 */
export function useLegalEntries(): FooterEntry[] {
  const { i18n } = useTranslation();
  const locale = i18n.language.slice(0, 2);
  const [entries, setEntries] = useState<FooterEntry[]>([]);

  useEffect(() => {
    let active = true;
    api
      .get<FooterEntry[]>(`/api/legal?locale=${encodeURIComponent(locale)}`)
      .then((list) => {
        if (active) {
          setEntries(list);
        }
      })
      .catch(() => {
        if (active) {
          setEntries([]);
        }
      });
    return () => {
      active = false;
    };
  }, [locale]);

  return entries;
}
