// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { useFeedback } from './FeedbackContext';

/**
 * "Report this" on one of the shell's own error states (ARCHITECTURE §20.1), carrying that state's code into the
 * report. A link to the operator's own channel when that is where feedback goes, and nothing when there is
 * nowhere to send it.
 */
export function ReportThis({ code, className = 'mc-btn' }: { code: string; className?: string }) {
  const { t } = useTranslation();
  const { route, open } = useFeedback();
  if (route.kind === 'external') {
    return (
      <a className={className} href={route.url} target="_blank" rel="noreferrer noopener">
        {t('feedback.report')}
      </a>
    );
  }
  if (route.kind !== 'form') {
    return null;
  }
  return (
    <button type="button" className={className} onClick={() => open(code)}>
      {t('feedback.report')}
    </button>
  );
}

/** The footer's entrance: the form, the operator's channel, or nothing. */
export function FeedbackLink() {
  const { t } = useTranslation();
  const { route, open } = useFeedback();
  if (route.kind === 'external') {
    return (
      <a className="mc-foot__feedback" href={route.url} target="_blank" rel="noreferrer noopener">
        {t('feedback.link')}
      </a>
    );
  }
  if (route.kind !== 'form') {
    return null;
  }
  return (
    <button type="button" className="mc-foot__feedback mc-linkbutton" onClick={() => open()}>
      {t('feedback.link')}
    </button>
  );
}
