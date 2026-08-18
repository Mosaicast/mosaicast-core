// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { formatDuration } from '../util/format';
import { absoluteUrl, SHARE_TARGETS, shareHref } from '../util/shareTargets';
import { parseTimestamp, withTimestamp } from '../util/timestamp';
import { Modal } from './Modal';

/** The glyph shown for each prepared destination — literal characters, as everywhere else in the shell. */
const TARGET_GLYPH: Record<string, string> = {
  whatsapp: '🟢',
  telegram: '✈',
  email: '✉',
};

/**
 * The share sheet (ARCHITECTURE §6.4) — one dialog for all three scopes.
 *
 * On an episode it carries the **start-at row**: a checkbox and a time prefilled with wherever the player
 * currently is, editable to any other moment. That is the mechanism every listener already knows from
 * YouTube, and it is what makes a timestamped link something people produce rather than something only
 * people who know the trick can type.
 *
 * The prepared destinations are plain links (see `util/shareTargets`) — nothing loads before a click, so
 * this surface makes no consent decision and needs no CSP host. A feed or site share carries the filter
 * query it was opened with, because a filtered view is part of what was being shared (§6.1).
 */
export function ShareDialog({
  path,
  title,
  onClose,
  atTime,
}: {
  /** Root-relative path being shared, including any filter query; a stray `t` is replaced. */
  path: string;
  /** Episode, feed or site title — the message text and mail subject. */
  title: string;
  onClose: () => void;
  /** Present for an episode only: where the player is now, in seconds. */
  atTime?: { current: number };
}) {
  const { t } = useTranslation();
  const fieldId = useId();
  const [enabled, setEnabled] = useState(false);
  // Captured once, on open: the prefill is "where you are", and a field that kept moving under the cursor
  // while the episode played would be unusable.
  const [text, setText] = useState(() => formatDuration(Math.floor(atTime?.current ?? 0)));
  const [copied, setCopied] = useState(false);

  const parsed = parseTimestamp(text);
  const invalid = enabled && parsed == null;
  const url = absoluteUrl(withTimestamp(path, enabled && parsed != null ? parsed : null));

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be refused (permissions, insecure origin). The URL is on screen and
      // selectable, so there is still a way to take it — saying nothing is better than a false success.
    }
  };

  // Only offered where it exists: on a desktop browser without it, a button that throws would be worse
  // than one that was never there.
  const canShareNatively = typeof navigator !== 'undefined' && typeof navigator.share === 'function';

  return (
    <Modal label={t('share.heading')} onClose={onClose}>
      <div className="mc-share">
        <h2 className="mc-share__title">{t('share.heading')}</h2>

        <div className="mc-share__targets">
          {SHARE_TARGETS.map((id) => (
            <a
              key={id}
              className="mc-share__target"
              href={shareHref(id, url, title)}
              target="_blank"
              rel="noopener noreferrer"
            >
              <span className="mc-share__glyph" aria-hidden="true">
                {TARGET_GLYPH[id]}
              </span>
              {t(`share.${id}`)}
            </a>
          ))}
          {canShareNatively && (
            <button
              type="button"
              className="mc-share__target"
              onClick={() => void navigator.share({ title, url }).catch(() => {})}
            >
              <span className="mc-share__glyph" aria-hidden="true">
                ⇪
              </span>
              {t('share.native')}
            </button>
          )}
        </div>

        {atTime && (
          <div className="mc-share__time">
            <label className="mc-share__at" htmlFor={`${fieldId}-on`}>
              <input
                id={`${fieldId}-on`}
                type="checkbox"
                checked={enabled}
                onChange={(event) => setEnabled(event.target.checked)}
              />
              {t('share.startAt')}
            </label>
            <input
              className="mc-input mc-share__clock"
              type="text"
              inputMode="numeric"
              value={text}
              disabled={!enabled}
              aria-label={t('share.timeLabel')}
              aria-invalid={invalid || undefined}
              aria-describedby={invalid ? `${fieldId}-err` : undefined}
              onChange={(event) => setText(event.target.value)}
            />
            {invalid && (
              <p className="mc-share__error" id={`${fieldId}-err`} role="alert">
                {t('share.invalidTime')}
              </p>
            )}
          </div>
        )}

        <div className="mc-share__link">
          <input
            className="mc-input mc-share__url"
            type="text"
            readOnly
            value={url}
            aria-label={t('share.link')}
            onFocus={(event) => event.currentTarget.select()}
          />
          <button type="button" className="mc-btn mc-btn--accent" onClick={() => void copy()}>
            {copied ? t('share.copied') : t('share.copy')}
          </button>
        </div>
        {/* The button's own label changing is invisible to a screen reader that is not on it. */}
        <p className="mc-sr-only" aria-live="polite">
          {copied ? t('share.copied') : ''}
        </p>

        <div className="mc-share__actions">
          <button type="button" className="mc-btn mc-btn--ghost" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </Modal>
  );
}
