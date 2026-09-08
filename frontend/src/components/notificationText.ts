// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { NotificationView } from '../api/types';

/**
 * What one notification says, which depends entirely on who sent it (ARCHITECTURE §17).
 *
 * Shared by the bell panel and the notifications page so the three sources cannot be read one way in one
 * place and another way in the other.
 *
 * - `system` — a fixed kind whose wording the shell owns and translates, so an admin who may not choose a
 *   reverted name (§8.6.1) cannot author the message about it either.
 * - `admin` — text somebody wrote. Rendered as text; React escapes it, and it never becomes markup.
 * - `plugin:<id>` — one finished sentence per locale, because the plugin could not know which language the
 *   reader would have when they opened it. Falls back to English, the one language a site cannot switch
 *   off (§12.7).
 */
export function describeNotification(
  item: NotificationView,
  t: (key: string, opts?: Record<string, unknown>) => string,
  locale: string,
): string {
  if (item.source === 'system' && item.kind) {
    return t(`notifications.kind.${item.kind}`, item.payload);
  }
  if (item.source === 'admin') {
    return item.payload.text ?? '';
  }
  return item.payload[locale] ?? item.payload.en ?? '';
}
