// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import type { NotificationView, Paged } from '../api/types';
import { formatDate } from '../util/format';
import { Dropdown } from './Dropdown';
import { Icon } from './Icon';

/** How many rows the panel shows. Past this the inbox is a page, not a dropdown. */
const PANEL_SIZE = 10;

/**
 * The inbox (ARCHITECTURE §17): a bell with an unread count, and a panel listing what it holds.
 *
 * Rendered only for a signed-in visitor — an anonymous one has no inbox and no endpoint to ask.
 *
 * The count is fetched on mount rather than polled. A notification nobody is waiting for does not justify
 * a request every few seconds from every open tab, and the surfaces that create one (an admin acting, a
 * plugin's timer) are not things a reader is watching for in real time.
 */
export function NotificationBell() {
  const { t, i18n } = useTranslation();
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<NotificationView[]>([]);
  const [loaded, setLoaded] = useState(false);

  const loadCount = () =>
    api
      .get<{ unread: number }>('/api/me/notifications/unread-count')
      .then((r) => setUnread(r.unread))
      .catch(() => {});

  useEffect(() => {
    void loadCount();
  }, []);

  // The list is fetched when the panel first opens, not on mount: most page loads never open it, and the
  // count alone is what the bell needs to render.
  const open = () => {
    if (loaded) {
      return;
    }
    setLoaded(true);
    api
      .get<Paged<NotificationView>>(`/api/me/notifications?page=0&size=${PANEL_SIZE}`)
      .then((paged) => setItems(paged.items))
      .catch(() => setLoaded(false));
  };

  const markAllRead = async () => {
    const { unread: left } = await api.post<{ unread: number }>('/api/me/notifications/read');
    setUnread(left);
    setItems((current) =>
      current.map((n) => (n.readAt ? n : { ...n, readAt: new Date().toISOString() })),
    );
  };

  return (
    <Dropdown
      trigger={
        <>
          <Icon name="bell" />
          {/*
            Decoration: the trigger's own `aria-label` already says how many unread there are, so
            announcing the digits again would read the same thing twice.
          */}
          {unread > 0 && (
            <span className="mc-bell__count" aria-hidden="true">
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </>
      }
      triggerClassName="mc-btn mc-btn--ghost mc-bell"
      ariaLabel={unread > 0 ? t('notifications.withUnread', { count: unread }) : t('notifications.title')}
      onOpen={open}
    >
      <div className="mc-inbox">
        <div className="mc-inbox__head">
          <span>{t('notifications.title')}</span>
          {unread > 0 && (
            <button type="button" className="mc-btn mc-btn--sm" onClick={markAllRead}>
              {t('notifications.markAllRead')}
            </button>
          )}
        </div>
        {items.length === 0 ? (
          <p className="mc-muted mc-inbox__empty">{t('notifications.empty')}</p>
        ) : (
          <ul className="mc-inbox__list">
            {items.map((item) => (
              <li key={item.id} className={`mc-inbox__row${item.readAt ? '' : ' mc-inbox__row--unread'}`}>
                {item.link ? (
                  <Link to={item.link}>{describe(item, t, i18n.language)}</Link>
                ) : (
                  <span>{describe(item, t, i18n.language)}</span>
                )}
                <span className="mc-muted mc-inbox__when">
                  {formatDate(item.createdAt, i18n.language)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Dropdown>
  );
}

/**
 * What one notification says, which depends entirely on who sent it (ARCHITECTURE §17).
 *
 * - `system` — a fixed kind whose wording the shell owns and translates, so an admin who may not choose a
 *   reverted name (§8.6.1) cannot author the message about it either.
 * - `admin` — text somebody wrote. Rendered as text; React escapes it, and it never becomes markup.
 * - `plugin:<id>` — one finished sentence per locale, because the plugin could not know which language the
 *   reader would have when they opened it. Fall back to English, the one language a site cannot switch off.
 */
function describe(
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
