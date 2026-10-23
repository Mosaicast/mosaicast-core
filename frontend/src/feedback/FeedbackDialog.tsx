// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';

import { announce } from '../a11y/LiveRegion';
import { api, ApiError } from '../api/client';
import { useMeta } from '../api/MetaContext';
import { Modal } from '../components/Modal';
import { usePluginRegistry } from '../plugins/PluginRegistry';
import { mountedPluginIds } from './mountedPlugins';
import type { Solver } from './pow';

const MAX_MESSAGE = 2000;
const MAX_LINKS = 2;
const LINK = /\b(?:https?:\/\/|www\.)\S+/gi;
const KINDS = ['problem', 'idea', 'other'] as const;
/** The refusals the form words itself (§20.4); anything else is the generic "didn't send". */
const KNOWN_REFUSALS = new Set([
  'feedback-invalid',
  'feedback-challenge-failed',
  'feedback-rate-limited',
  'feedback-busy',
  'feedback-blocked',
]);

/**
 * The feedback form (ARCHITECTURE §20). Works without an account, because the commonest thing to report is a
 * sign-in that failed; an anonymous sender's browser solves a short proof of work before sending, which costs
 * a person a second they do not notice.
 *
 * It shows what goes with the report before it is sent — the page, the error it came from, what else is on the
 * page, the site's version — because a report is more useful with them and a sender should know what they are
 * handing over. The server checks every part and adds the versions itself.
 */
export function FeedbackDialog({
  error,
  signedIn,
  solve,
  onClose,
}: {
  error?: string;
  signedIn: boolean;
  solve: Solver;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const meta = useMeta();
  const { plugins } = usePluginRegistry();
  const [kind, setKind] = useState<(typeof KINDS)[number]>(error ? 'problem' : 'idea');
  const [message, setMessage] = useState('');
  const [contact, setContact] = useState('');
  const [website, setWebsite] = useState('');
  const [state, setState] = useState<'compose' | 'sending' | 'sent'>('compose');
  const [failure, setFailure] = useState<string | null>(null);

  // Taken once, when the form opens: the page it was opened from is the page the report is about.
  const [context] = useState(() => ({ route: location.pathname, plugins: mountedPluginIds(), error }));
  const partNames = useMemo(
    () =>
      context.plugins
        .map((id) => plugins.find((plugin) => plugin.id === id)?.name ?? null)
        .filter((name): name is string => !!name),
    [context.plugins, plugins],
  );

  const links = (message.match(LINK) ?? []).length;
  const tooManyLinks = !signedIn && links > MAX_LINKS;
  const ready = message.trim().length > 0 && !tooManyLinks && state === 'compose';

  const send = async () => {
    setFailure(null);
    setState('sending');
    try {
      let proof: { challenge?: string; solution?: string } = {};
      if (!signedIn) {
        const issued = await api.get<{ challenge: string; bits: number }>('/api/feedback/challenge');
        proof = { challenge: issued.challenge, solution: await solve(issued.challenge, issued.bits) };
      }
      await api.post('/api/feedback', {
        kind,
        message: message.trim(),
        contact: signedIn ? null : contact.trim() || null,
        locale: i18n.language,
        context,
        website,
        ...proof,
      });
      setState('sent');
      announce(t('feedback.sent'));
    } catch (err) {
      const slug = err instanceof ApiError ? (err.problem?.type ?? '').split('/').pop() ?? '' : '';
      setFailure(t(KNOWN_REFUSALS.has(slug) ? `feedback.error.${slug}` : 'feedback.error.generic'));
      setState('compose');
    }
  };

  if (state === 'sent') {
    return (
      <Modal label={t('feedback.title')} onClose={onClose}>
        <h2 className="mc-dialog__title">{t('feedback.title')}</h2>
        <p role="status">{t('feedback.sent')}</p>
        <div className="mc-dialog__actions">
          <button type="button" className="mc-btn mc-btn--accent" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal label={t('feedback.title')} onClose={onClose}>
      <h2 className="mc-dialog__title">{t('feedback.title')}</h2>
      <form
        className="mc-form mc-feedback"
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) {
            void send();
          }
        }}
      >
        <fieldset className="mc-options">
          <legend>{t('feedback.kind.legend')}</legend>
          {KINDS.map((value) => (
            <label key={value} className="mc-toggle">
              <input
                type="radio"
                name="feedback-kind"
                value={value}
                checked={kind === value}
                onChange={() => setKind(value)}
              />
              <span>{t(`feedback.kind.${value}`)}</span>
            </label>
          ))}
        </fieldset>

        <label className="mc-field">
          <span>{t('feedback.message')}</span>
          <textarea
            className="mc-textarea"
            rows={5}
            maxLength={MAX_MESSAGE}
            value={message}
            aria-invalid={tooManyLinks || undefined}
            aria-describedby="mc-feedback-count"
            onChange={(event) => setMessage(event.target.value)}
          />
        </label>
        <span id="mc-feedback-count" className="mc-muted mc-feedback__count">
          {t('feedback.count', { used: message.length, max: MAX_MESSAGE })}
        </span>
        {tooManyLinks && (
          <p className="mc-error" role="alert">
            {t('feedback.tooManyLinks', { max: MAX_LINKS })}
          </p>
        )}

        {!signedIn && (
          <label className="mc-field">
            <span>{t('feedback.contact')}</span>
            <input
              className="mc-input"
              type="text"
              autoComplete="email"
              maxLength={200}
              value={contact}
              aria-describedby="mc-feedback-contact-hint"
              onChange={(event) => setContact(event.target.value)}
            />
          </label>
        )}
        {!signedIn && (
          <span id="mc-feedback-contact-hint" className="mc-muted">
            {t('feedback.contactHint')}
          </span>
        )}

        {/* No person sees or fills this in; a report with it filled is answered as sent and dropped (§20.4). */}
        <label className="mc-feedback__trap" aria-hidden="true">
          <span>Website</span>
          <input
            type="text"
            tabIndex={-1}
            autoComplete="off"
            value={website}
            onChange={(event) => setWebsite(event.target.value)}
          />
        </label>

        <div className="mc-feedback__context">
          <p className="mc-pluginrow__storageTitle">{t('feedback.context')}</p>
          <ul className="mc-muted">
            <li>{t('feedback.contextPage', { page: context.route })}</li>
            {error && <li>{t('feedback.contextError', { error: t(`feedback.code.${errorLabel(error)}`) })}</li>}
            {partNames.length > 0 && <li>{t('feedback.contextParts', { names: partNames.join(', ') })}</li>}
            {meta?.version && <li>{t('feedback.contextVersion', { version: meta.version })}</li>}
          </ul>
        </div>

        {failure && (
          <p className="mc-error" role="alert">
            {failure}
          </p>
        )}

        <div className="mc-dialog__actions">
          <button type="button" className="mc-btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="mc-btn mc-btn--accent" disabled={!ready}>
            {state === 'sending' ? t('feedback.sending') : t('feedback.send')}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Every sign-in failure reads the same to a sender; the shell's own states keep their names. */
function errorLabel(code: string): string {
  return ['crash', 'not-found', 'load-failed'].includes(code) ? code : 'login';
}
