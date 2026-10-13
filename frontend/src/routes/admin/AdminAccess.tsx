// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import { problemMessage } from '../../api/problemMessage';
import type { AdminSiteAccessView, PassFloor, SiteState } from '../../api/types';
import { SavedNote } from '../../a11y/SavedNote';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { fromLocalInput, localZoneName, toLocalInput } from '../../util/localDateTime';

const STATES: SiteState[] = ['open', 'scheduled', 'maintenance', 'closed'];

/** What the form holds; times stay `datetime-local` strings in the admin's zone until sent. */
interface Draft {
  state: SiteState;
  opensAt: string;
  endsAt: string;
  passFloor: PassFloor;
  staffLoginVisible: boolean;
  signupsWhileClosed: boolean;
  edgeCounting: boolean;
}

function draftOf(view: AdminSiteAccessView): Draft {
  return {
    state: view.access.state,
    opensAt: view.access.opensAt ? toLocalInput(view.access.opensAt) : '',
    endsAt: view.access.endsAt ? toLocalInput(view.access.endsAt) : '',
    passFloor: view.access.passFloor,
    staffLoginVisible: view.access.staffLogin.visible,
    signupsWhileClosed: view.signupsWhileClosed,
    edgeCounting: view.access.edgeCounting ?? true,
  };
}

/**
 * Site access (ARCHITECTURE §18, ADMIN only): whether visitors see the site, a countdown, maintenance or a
 * closed page, and who gets through meanwhile.
 *
 * Core only holds the policy; the edge in front of it shows the pages. So the page says plainly whether an
 * edge is reporting in — a "closed" setting with nothing enforcing it is a site that is open while its admin
 * believes otherwise, and the one part core enforces itself (sign-ups) is named so it is not mistaken for all.
 */
export function AdminAccess() {
  const { t, i18n } = useTranslation();

  const [view, setView] = useState<AdminSiteAccessView | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const show = (next: AdminSiteAccessView) => {
    setView(next);
    setDraft(draftOf(next));
  };

  useEffect(() => {
    api
      .get<AdminSiteAccessView>('/api/admin/site/access')
      .then(show)
      .catch(() => setFailed(true));
  }, []);

  const when = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  const save = async () => {
    if (!draft) return;
    setSaved(false);
    setError(null);
    try {
      show(
        await api.put<AdminSiteAccessView>('/api/admin/site/access', {
          state: draft.state,
          opensAt: draft.state === 'scheduled' ? fromLocalInput(draft.opensAt) : null,
          endsAt: draft.state === 'maintenance' ? fromLocalInput(draft.endsAt) : null,
          passFloor: draft.passFloor,
          staffLoginVisible: draft.staffLoginVisible,
          signupsWhileClosed: draft.signupsWhileClosed,
          edgeCounting: draft.edgeCounting,
        }),
      );
      setSaved(true);
    } catch (e) {
      setError(problemMessage(e, t, t('admin.access.saveFailed')));
    }
  };

  const openNow = async () => {
    setConfirmOpen(false);
    setSaved(false);
    setError(null);
    try {
      show(await api.post<AdminSiteAccessView>('/api/admin/site/access/open-now'));
      setSaved(true);
    } catch (e) {
      setError(problemMessage(e, t, t('admin.access.saveFailed')));
    }
  };

  if (failed) {
    return <p className="mc-muted">{t('admin.access.loadFailed')}</p>;
  }
  if (!view || !draft) {
    return null;
  }

  const set = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch });
  const current = view.access;
  const isOpen = current.effectiveState === 'open';

  return (
    <div className="mc-form">
      <h2>{t('admin.access.title')}</h2>
      <p className="mc-muted">{t('admin.access.intro')}</p>

      <p className="mc-access__now" role="status">
        {current.effectiveState === 'scheduled' && current.opensAt
          ? t('admin.access.now.scheduled', { when: when(current.opensAt) })
          : t(`admin.access.now.${current.effectiveState}`)}
      </p>

      <p className={view.edge.status === 'connected' ? 'mc-muted' : 'mc-access__warning'}>
        {view.edge.status === 'connected' && view.edge.lastSeen
          ? t('admin.access.edge.connected', {
              when: when(view.edge.lastSeen),
              build: view.edge.build ?? '?',
            })
          : t(`admin.access.edge.${view.edge.status}`)}
      </p>

      {error && <p className="mc-error">{error}</p>}

      <fieldset className="mc-options">
        <legend>{t('admin.access.state')}</legend>
        {STATES.map((value) => (
          <label key={value} className="mc-toggle">
            <input
              type="radio"
              name="site-state"
              value={value}
              checked={draft.state === value}
              onChange={() => set({ state: value })}
            />
            <span>
              {t(`admin.access.state.${value}`)}
              <span className="mc-muted"> — {t(`admin.access.state.${value}.hint`)}</span>
            </span>
          </label>
        ))}
      </fieldset>

      {draft.state === 'scheduled' && (
        <label className="mc-field">
          <span>{t('admin.access.opensAt', { zone: localZoneName() })}</span>
          <input
            className="mc-input"
            type="datetime-local"
            value={draft.opensAt}
            onChange={(event) => set({ opensAt: event.target.value })}
          />
        </label>
      )}

      {draft.state === 'maintenance' && (
        <label className="mc-field">
          <span>{t('admin.access.endsAt', { zone: localZoneName() })}</span>
          <input
            className="mc-input"
            type="datetime-local"
            value={draft.endsAt}
            onChange={(event) => set({ endsAt: event.target.value })}
          />
          <span className="mc-muted">{t('admin.access.endsAt.hint')}</span>
        </label>
      )}

      <fieldset className="mc-options">
        <legend>{t('admin.access.passFloor')}</legend>
        {(['podcaster', 'admin'] as const).map((value) => (
          <label key={value} className="mc-toggle">
            <input
              type="radio"
              name="pass-floor"
              value={value}
              checked={draft.passFloor === value}
              onChange={() => set({ passFloor: value })}
            />
            <span>{t(`admin.access.passFloor.${value}`)}</span>
          </label>
        ))}
      </fieldset>

      <fieldset className="mc-options">
        <legend>{t('admin.access.while')}</legend>
        <label className="mc-toggle">
          <input
            type="checkbox"
            checked={draft.staffLoginVisible}
            onChange={(event) => set({ staffLoginVisible: event.target.checked })}
          />
          <span>
            {t('admin.access.staffLogin')}
            <span className="mc-muted"> — {t('admin.access.staffLogin.hint')}</span>
          </span>
        </label>
        <label className="mc-toggle">
          <input
            type="checkbox"
            checked={draft.signupsWhileClosed}
            onChange={(event) => set({ signupsWhileClosed: event.target.checked })}
          />
          <span>
            {t('admin.access.signups')}
            <span className="mc-muted"> — {t('admin.access.signups.hint')}</span>
          </span>
        </label>
      </fieldset>

      <fieldset className="mc-options">
        <legend>{t('admin.access.edgeCounting.legend')}</legend>
        <label className="mc-toggle">
          <input
            type="checkbox"
            checked={draft.edgeCounting}
            onChange={(event) => set({ edgeCounting: event.target.checked })}
          />
          <span>
            {t('admin.access.edgeCounting')}
            <span className="mc-muted"> — {t('admin.access.edgeCounting.hint')}</span>
          </span>
        </label>
      </fieldset>

      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={save}>
          {t('admin.access.save')}
        </button>
        {!isOpen && (
          <button type="button" className="mc-btn" onClick={() => setConfirmOpen(true)}>
            {t('admin.access.openNow')}
          </button>
        )}
        <SavedNote show={saved}>{t('admin.access.saved')}</SavedNote>
      </div>

      {confirmOpen && (
        <ConfirmDialog
          title={t('admin.access.openNow')}
          body={t('admin.access.openNow.confirm')}
          confirmLabel={t('admin.access.openNow')}
          onConfirm={() => void openNow()}
          onCancel={() => setConfirmOpen(false)}
        />
      )}
    </div>
  );
}
