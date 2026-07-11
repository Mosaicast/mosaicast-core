// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

/**
 * Surfaces a failed social login (ARCHITECTURE §8.3): the host redirects to `/?login_error=<code>` — the
 * account-merging outcomes `account_conflict` / `link_required` (M3 fix #9), else `failed`. Shows a
 * dismissible message and clears the query param.
 */
const KNOWN = new Set(['account_conflict', 'link_required', 'failed']);

export function LoginErrorBanner() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const code = params.get('login_error');
  if (!code) {
    return null;
  }
  const key = KNOWN.has(code) ? code : 'failed';
  const dismiss = () => {
    const next = new URLSearchParams(params);
    next.delete('login_error');
    setParams(next, { replace: true });
  };
  return (
    <div className="mc-banner mc-banner--error" role="alert">
      <span>{t(`loginError.${key}`)}</span>
      <button type="button" className="mc-banner__close" onClick={dismiss} aria-label={t('common.dismiss')}>
        ×
      </button>
    </div>
  );
}
