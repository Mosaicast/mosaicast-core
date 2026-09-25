// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import type { NotificationView, Paged } from '../api/types';
import { useUser } from '../auth/UserContext';
import { NotificationRow } from '../components/NotificationRow';
import { useDocumentTitle } from '../a11y/documentTitle';

const PAGE_SIZE = 25;

/**
 * The whole inbox (ARCHITECTURE §17).
 *
 * The bell panel shows the newest handful and links here for the rest — a dropdown that grows with the
 * backlog stops being a dropdown, and the endpoint has paged from the start.
 */
export function NotificationsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t('notifications.title'));
  const { user } = useUser();
  const [items, setItems] = useState<NotificationView[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [unread, setUnread] = useState(0);

  const load = () => {
    api
      .get<Paged<NotificationView>>(`/api/me/notifications?page=${page}&size=${PAGE_SIZE}`)
      .then((paged) => {
        setItems(paged.items);
        setTotalPages(Math.max(1, paged.totalPages));
      })
      .catch(() => {});
    api
      .get<{ unread: number }>('/api/me/notifications/unread-count')
      .then((r) => setUnread(r.unread))
      .catch(() => {});
  };
  useEffect(load, [page]);

  if (!user) {
    return null;
  }

  const markRead = async (id: string) => {
    await api.post(`/api/me/notifications/${id}/read`);
    // Updated in place rather than refetched: a reload would reorder nothing but would make the row the
    // reader just acted on jump, which reads as the click having done something else.
    setItems((current) =>
      current.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)),
    );
    setUnread((n) => Math.max(0, n - 1));
  };

  const markAllRead = async () => {
    await api.post('/api/me/notifications/read');
    const now = new Date().toISOString();
    setItems((current) => current.map((n) => (n.readAt ? n : { ...n, readAt: now })));
    setUnread(0);
  };

  return (
    <section className="mc-page mc-inbox-page">
      <h1 className="mc-page__title">{t('notifications.title')}</h1>
      {unread > 0 && (
        <button type="button" className="mc-btn" onClick={markAllRead}>
          {t('notifications.markAllRead')}
        </button>
      )}

      {items.length === 0 ? (
        <p className="mc-muted">{t('notifications.empty')}</p>
      ) : (
        <ul className="mc-inbox__list mc-inbox__list--page">
          {items.map((item) => (
            <NotificationRow key={item.id} item={item} onMarkRead={markRead} />
          ))}
        </ul>
      )}

      {totalPages > 1 && (
        <div className="mc-logpanel__foot">
          <button type="button" className="mc-btn" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('notifications.previous')}
          </button>
          <span className="mc-muted">
            {t('notifications.pageOf', { page: page + 1, pages: totalPages })}
          </span>
          <button
            type="button"
            className="mc-btn"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('notifications.next')}
          </button>
        </div>
      )}
    </section>
  );
}
