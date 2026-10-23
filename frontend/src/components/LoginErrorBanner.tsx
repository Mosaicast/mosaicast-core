// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { ReportThis } from '../feedback/ReportThis';

/**
 * Surfaces a failed social login (ARCHITECTURE §8.3): the host redirects to `/?login_error=<code>` — the
 * account-merging outcomes `account_conflict` / `link_required` (M3 fix #9), the sign-up refusal of a site that
 * is not open yet `signups_closed` (§18.3), else `failed`. Shows a dismissible message, a way to report it —
 * the visitor this happens to has no account to report it from (§20.1) — and clears the query param.
 */
const KNOWN = new Set(['account_conflict', 'link_required', 'signups_closed', 'failed']);

export function LoginErrorBanner() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const code = params.get('login_error');
  if (!code) {
    return null;
  }
  // The server passes any OAuth error through; one this shell has no words for reads as a plain failure.
  const key = KNOWN.has(code) ? code : 'failed';
  const dismiss = () => {
    const next = new URLSearchParams(params);
    next.delete('login_error');
    setParams(next, { replace: true });
  };
  return (
    <div className="mc-banner mc-banner--error" role="alert">
      <span>{t(`loginError.${key}`)}</span>
      <span className="mc-banner__actions">
        <ReportThis code={key} className="mc-btn mc-btn--ghost" />
        <button type="button" className="mc-banner__close" onClick={dismiss} aria-label={t('common.dismiss')}>
          <span className="mc-banner__closeicon" aria-hidden="true" />
        </button>
      </span>
    </div>
  );
}
