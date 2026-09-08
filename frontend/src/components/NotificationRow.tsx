// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import type { NotificationView } from '../api/types';
import { formatDate } from '../util/format';
import { describeNotification } from './notificationText';

/**
 * One notification, in the bell panel or on the page (ARCHITECTURE §17).
 *
 * **Read is an explicit act.** Nothing here marks a notification read because it scrolled past — a reader
 * who glances at a bell has not read a warning, and read state is what an admin later relies on to know
 * somebody was told (§17). So there is a control, and following the link counts too, because that is a
 * click either way.
 *
 * There is deliberately no delete. Retention clears read notifications on its own (§17.2), and a user who
 * could delete an admin warning would erase the record that they received it.
 */
export function NotificationRow({
  item,
  onMarkRead,
}: {
  item: NotificationView;
  /** Called when the reader marks this one read, or follows its link. */
  onMarkRead: (id: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const text = describeNotification(item, t, i18n.language);
  const unread = item.readAt === null;

  return (
    <li className={`mc-inbox__row${unread ? ' mc-inbox__row--unread' : ''}`}>
      <div className="mc-inbox__body">
        {item.link ? (
          <Link to={item.link} onClick={() => unread && onMarkRead(item.id)}>
            {text}
          </Link>
        ) : (
          <span>{text}</span>
        )}
        <span className="mc-muted mc-inbox__when">{formatDate(item.createdAt, i18n.language)}</span>
      </div>
      {unread && (
        <button
          type="button"
          className="mc-inbox__read"
          aria-label={t('notifications.markRead')}
          title={t('notifications.markRead')}
          onClick={() => onMarkRead(item.id)}
        />
      )}
    </li>
  );
}
