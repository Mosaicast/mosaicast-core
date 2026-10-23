// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { SavedNote } from '../../a11y/SavedNote';
import { api, ApiError } from '../../api/client';
import { useRefreshMeta } from '../../api/MetaContext';
import { problemMessage } from '../../api/problemMessage';
import type { FeedbackReport, FeedbackSettings, Paged } from '../../api/types';
import { useUser } from '../../auth/UserContext';

const STATUSES = ['new', 'seen', 'done', 'spam'] as const;
const KINDS = ['problem', 'idea', 'other'] as const;
const MODES = ['everyone', 'signed-in', 'external', 'off'] as const;

/**
 * Admin → Feedback (ARCHITECTURE §20.3): the inbox, for whoever meets the operator's floor, and the settings,
 * for admins.
 *
 * A reader sees everything a report holds, the contact included. Answering goes in-app for a signed-in sender and
 * by the reader's own mail client for an anonymous one; forwarding copies a summary without the contact and opens
 * the project's tracker in the reader's browser, so nothing leaves the site unless the reader sends it.
 */
export function AdminFeedback() {
  const { t, i18n } = useTranslation();
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';
  const [status, setStatus] = useState<string>('');
  const [kind, setKind] = useState<string>('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Paged<FeedbackReport> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [open, setOpen] = useState<FeedbackReport | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const load = useCallback(() => {
    const query = new URLSearchParams({ page: String(page), size: '50' });
    if (status) query.set('status', status);
    if (kind) query.set('kind', kind);
    api
      .get<Paged<FeedbackReport>>(`/api/admin/feedback?${query}`)
      .then((r) => {
        setResult(r);
        setError(null);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 403) {
          setForbidden(true);
        } else {
          setError(problemMessage(e, t, t('admin.feedback.loadFailed')));
        }
      });
  }, [page, status, kind, t]);

  useEffect(load, [load]);

  const when = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  const openReport = async (id: string) => {
    if (open?.id === id) {
      setOpen(null);
      return;
    }
    try {
      const report = await api.get<FeedbackReport>(`/api/admin/feedback/${id}`);
      setOpen(report);
      replaceRow(report);
    } catch (e) {
      setError(problemMessage(e, t, t('admin.feedback.loadFailed')));
    }
  };

  const replaceRow = (report: FeedbackReport) =>
    setResult((r) => (r ? { ...r, items: r.items.map((item) => (item.id === report.id ? report : item)) } : r));

  const bulkSpam = async () => {
    try {
      await api.post('/api/admin/feedback/status', { status: 'spam', ids: [...selected] });
      setSelected(new Set());
      setOpen(null);
      load();
    } catch (e) {
      setError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  if (forbidden) {
    return <p className="mc-muted">{t('admin.forbidden')}</p>;
  }

  return (
    <div className="mc-form mc-feedbackadmin">
      <h2>{t('admin.feedback.title')}</h2>
      <p className="mc-muted">{t('admin.feedback.intro')}</p>
      {isAdmin && <FeedbackSettingsForm />}
      {error && <p className="mc-error">{error}</p>}

      <div className="mc-logfilters">
        <label className="mc-field">
          <span>{t('admin.feedback.filter.status')}</span>
          <select value={status} onChange={(e) => (setPage(0), setStatus(e.target.value))}>
            <option value="">{t('admin.feedback.filter.any')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`admin.feedback.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="mc-field">
          <span>{t('admin.feedback.filter.kind')}</span>
          <select value={kind} onChange={(e) => (setPage(0), setKind(e.target.value))}>
            <option value="">{t('admin.feedback.filter.any')}</option>
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`feedback.kind.${k}`)}
              </option>
            ))}
          </select>
        </label>
        {selected.size > 0 && (
          <button type="button" className="mc-btn mc-btn--danger" onClick={() => void bulkSpam()}>
            {t('admin.feedback.markSpam', { count: selected.size })}
          </button>
        )}
      </div>

      {result && result.items.length === 0 && <p className="mc-muted">{t('admin.feedback.empty')}</p>}
      {result && result.items.length > 0 && (
        <ul className="mc-feedbacklist">
          {result.items.map((report) => (
            <li key={report.id} className={`mc-feedbacklist__row mc-feedbacklist__row--${report.status}`}>
              <div className="mc-feedbacklist__head">
                <input
                  type="checkbox"
                  aria-label={t('admin.feedback.select')}
                  checked={selected.has(report.id)}
                  onChange={(e) => {
                    const next = new Set(selected);
                    if (e.target.checked) next.add(report.id);
                    else next.delete(report.id);
                    setSelected(next);
                  }}
                />
                <button
                  type="button"
                  className="mc-feedbacklist__open"
                  aria-expanded={open?.id === report.id}
                  onClick={() => void openReport(report.id)}
                >
                  <span className={`mc-chip mc-chip--feedback-${report.status}`}>
                    {t(`admin.feedback.status.${report.status}`)}
                  </span>
                  <span className="mc-chip">{t(`feedback.kind.${report.kind}`)}</span>
                  <span className="mc-feedbacklist__excerpt">{report.message}</span>
                  <span className="mc-muted mc-feedbacklist__meta">
                    {report.sender?.displayName ?? t('admin.feedback.anonymous')} · {when(report.createdAt)}
                  </span>
                </button>
              </div>
              {open?.id === report.id && (
                <ReportDetail
                  report={open}
                  onChanged={(next) => {
                    setOpen(next);
                    replaceRow(next);
                  }}
                  onError={(message) => setError(message)}
                />
              )}
            </li>
          ))}
        </ul>
      )}

      {result != null && result.totalPages > 1 && (
        <div className="mc-logpanel__foot">
          <button type="button" className="mc-btn" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('admin.logs.previous')}
          </button>
          <span className="mc-muted">
            {t('admin.logs.pageOf', { page: result.page + 1, pages: result.totalPages })}
          </span>
          <button
            type="button"
            className="mc-btn"
            disabled={result.page + 1 >= result.totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('admin.logs.next')}
          </button>
        </div>
      )}
    </div>
  );
}

/** One opened report: its whole message and context, and what a reader can do with it. */
function ReportDetail({
  report,
  onChanged,
  onError,
}: {
  report: FeedbackReport;
  onChanged: (next: FeedbackReport) => void;
  onError: (message: string) => void;
}) {
  const { t } = useTranslation();
  const [answer, setAnswer] = useState('');
  const [answered, setAnswered] = useState(false);
  const [copied, setCopied] = useState(false);

  const move = async (status: 'done' | 'spam') => {
    try {
      onChanged(await api.put<FeedbackReport>(`/api/admin/feedback/${report.id}/status`, { status }));
    } catch (e) {
      onError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  const reply = async () => {
    try {
      onChanged(await api.post<FeedbackReport>(`/api/admin/feedback/${report.id}/reply`, { text: answer }));
      setAnswer('');
      setAnswered(true);
    } catch (e) {
      onError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  const forward = async () => {
    try {
      const { text, tracker } = await api.get<{ text: string; tracker: string }>(
        `/api/admin/feedback/${report.id}/forward`,
      );
      await navigator.clipboard?.writeText(text).catch(() => {});
      setCopied(true);
      window.open(tracker, '_blank', 'noopener,noreferrer');
    } catch (e) {
      onError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  const block = async (blocked: boolean) => {
    if (!report.sender) return;
    try {
      const path = `/api/admin/feedback/senders/${report.sender.id}/block`;
      if (blocked) {
        await api.put(path);
      } else {
        await api.del(path);
      }
      onChanged({ ...report, sender: { ...report.sender, blocked } });
    } catch (e) {
      onError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  return (
    <div className="mc-feedbacklist__detail">
      <p className="mc-feedbacklist__message">{report.message}</p>
      <ul className="mc-muted mc-feedbacklist__context">
        {report.context.route && <li>{t('admin.feedback.context.page', { page: report.context.route })}</li>}
        {report.context.error && <li>{t('admin.feedback.context.error', { error: report.context.error })}</li>}
        {(report.context.plugins ?? []).map((plugin) => (
          <li key={plugin.id}>{t('admin.feedback.context.plugin', { id: plugin.id, version: plugin.version })}</li>
        ))}
        {report.context.core && <li>{t('admin.feedback.context.core', { version: report.context.core })}</li>}
        {report.locale && <li>{t('admin.feedback.context.locale', { locale: report.locale })}</li>}
      </ul>

      {report.sender ? (
        <>
          <p>{t('admin.feedback.from', { name: report.sender.displayName })}</p>
          <label className="mc-field">
            <span>{t('admin.feedback.answer')}</span>
            <textarea
              className="mc-textarea"
              rows={3}
              maxLength={1000}
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
            />
          </label>
          <SavedNote show={answered}>{t('admin.feedback.answered')}</SavedNote>
        </>
      ) : (
        report.contact && (
          <p>
            {t('admin.feedback.contact')}: <span className="mc-feedbacklist__contact">{report.contact}</span>
          </p>
        )
      )}

      <div className="mc-form__actions">
        {report.sender && (
          <button type="button" className="mc-btn mc-btn--accent" disabled={!answer.trim()} onClick={() => void reply()}>
            {t('admin.feedback.send')}
          </button>
        )}
        {!report.sender && report.contact?.includes('@') && (
          <a className="mc-btn" href={`mailto:${report.contact}`}>
            {t('admin.feedback.mail')}
          </a>
        )}
        <button type="button" className="mc-btn" onClick={() => void move('done')}>
          {t('admin.feedback.done')}
        </button>
        <button type="button" className="mc-btn" onClick={() => void move('spam')}>
          {t('admin.feedback.spam')}
        </button>
        <button type="button" className="mc-btn" onClick={() => void forward()}>
          {t('admin.feedback.forward')}
        </button>
        {report.sender && (
          <button type="button" className="mc-btn mc-btn--danger" onClick={() => void block(!report.sender?.blocked)}>
            {report.sender.blocked ? t('admin.feedback.unblock') : t('admin.feedback.block')}
          </button>
        )}
      </div>
      <SavedNote show={copied} as="p">
        {t('admin.feedback.forwardCopied')}
      </SavedNote>
    </div>
  );
}

/** Who may send, where an external channel is, and who reads (§20.1). Admins only. */
function FeedbackSettingsForm() {
  const { t } = useTranslation();
  const refreshMeta = useRefreshMeta();
  const [draft, setDraft] = useState<FeedbackSettings | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<FeedbackSettings>('/api/admin/feedback/settings')
      .then(setDraft)
      .catch((e) => setError(problemMessage(e, t, t('admin.feedback.loadFailed'))));
  }, [t]);

  if (!draft) {
    return error ? <p className="mc-error">{error}</p> : null;
  }

  const save = async () => {
    setSaved(false);
    setError(null);
    try {
      setDraft(await api.put<FeedbackSettings>('/api/admin/feedback/settings', draft));
      refreshMeta();
      setSaved(true);
    } catch (e) {
      setError(problemMessage(e, t, t('admin.feedback.saveFailed')));
    }
  };

  return (
    <section className="mc-feedbackadmin__settings">
      <fieldset className="mc-options">
        <legend>{t('admin.feedback.mode')}</legend>
        {MODES.map((mode) => (
          <label key={mode} className="mc-toggle">
            <input
              type="radio"
              name="feedback-mode"
              checked={draft.mode === mode}
              onChange={() => setDraft({ ...draft, mode })}
            />
            <span>{t(`admin.feedback.mode.${mode}`)}</span>
          </label>
        ))}
      </fieldset>
      {draft.mode === 'external' && (
        <label className="mc-field">
          <span>{t('admin.feedback.url')}</span>
          <input
            className="mc-input"
            type="text"
            value={draft.url ?? ''}
            placeholder="https://"
            onChange={(e) => setDraft({ ...draft, url: e.target.value })}
          />
        </label>
      )}
      <fieldset className="mc-options">
        <legend>{t('admin.feedback.floor')}</legend>
        {(['admin', 'podcaster'] as const).map((floor) => (
          <label key={floor} className="mc-toggle">
            <input
              type="radio"
              name="feedback-floor"
              checked={draft.floor === floor}
              onChange={() => setDraft({ ...draft, floor })}
            />
            <span>{t(`admin.feedback.floor.${floor}`)}</span>
          </label>
        ))}
        <p className="mc-muted">{t('admin.feedback.floorHint')}</p>
      </fieldset>
      {error && <p className="mc-error">{error}</p>}
      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={() => void save()}>
          {t('common.save')}
        </button>
        <SavedNote show={saved}>{t('admin.feedback.saved')}</SavedNote>
      </div>
    </section>
  );
}
