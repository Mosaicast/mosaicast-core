// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { Role } from '../api/types';
import { useUser } from './UserContext';

/**
 * Route guard (ARCHITECTURE §8.5): renders its children only for a user in one of `roles`. While the user is
 * still loading it shows nothing; an anonymous user is sent home; a wrong role gets a 403 note. The server
 * enforces access too — this only keeps the UI honest.
 */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { t } = useTranslation();
  const { user, loading } = useUser();

  if (loading) {
    return null;
  }
  if (!user) {
    // Said, not redirected: a visitor who followed a link to /admin or /account was dropped on the front page
    // with nothing to say why, while a signed-in fan got an explanation (#199). Staying on the URL also means
    // signing in from the header shows the page they asked for.
    return (
      <section className="mc-page">
        <h1 className="mc-page__title">{t('auth.signInRequired')}</h1>
        <p className="mc-muted">{t('auth.signInRequiredBody')}</p>
      </section>
    );
  }
  if (!roles.includes(user.role)) {
    return (
      <section className="mc-page">
        <h1 className="mc-page__title">{t('admin.forbidden')}</h1>
        <p className="mc-muted">{t('admin.forbiddenBody')}</p>
      </section>
    );
  }
  return <>{children}</>;
}
