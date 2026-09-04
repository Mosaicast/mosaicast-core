// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import type { NotificationView, Paged } from '../api/types';
import { Dropdown } from './Dropdown';
import { Icon } from './Icon';
import { NotificationRow } from './NotificationRow';

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
  const { t } = useTranslation();
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<NotificationView[]>([]);
  const [loaded, setLoaded] = useState(false);
  /** How many the inbox holds in total, so the panel can offer the rest rather than a total. */
  const [total, setTotal] = useState(0);

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
      .then((paged) => {
        setItems(paged.items);
        setTotal(paged.totalElements);
      })
      .catch(() => setLoaded(false));
  };

  const markRead = async (id: string) => {
    await api.post(`/api/me/notifications/${id}/read`);
    setItems((current) =>
      current.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)),
    );
    setUnread((n) => Math.max(0, n - 1));
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
              {unread}
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
          <>
            <ul className="mc-inbox__list">
              {items.map((item) => (
                <NotificationRow key={item.id} item={item} onMarkRead={markRead} />
              ))}
            </ul>
            {/*
              How many are *left*, not how many there are: the panel already shows the newest, and a
              reader deciding whether to open the full page wants to know what they have not seen.
            */}
            {total > items.length && (
              <Link className="mc-inbox__more" to="/notifications">
                {t('notifications.more', { count: total - items.length })}
              </Link>
            )}
          </>
        )}
      </div>
    </Dropdown>
  );
}
