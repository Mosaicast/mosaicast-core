// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../api/client';
import type { DataExportView } from '../api/types';
import { formatDate, formatFileSize } from '../util/format';

/** How often a running export is checked on while the page is open. */
const POLL_MS = 4000;

/**
 * "Download your data" on the account page (ARCHITECTURE §12.8.1).
 *
 * The archive is built off the request — every plugin is asked, each with a time limit — so the button starts
 * a job and this shows where it stands: preparing (checked on while the page is open, and a notification
 * arrives when it is done either way), ready with its expiry, failed, or expired. The download is a plain
 * link, so the browser streams the ZIP to disk rather than this page holding it in memory.
 */
export function DataExport() {
  const { t, i18n } = useTranslation();
  const [latest, setLatest] = useState<DataExportView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  const load = useCallback(async () => {
    try {
      // 204 for an account that never asked; the client resolves that as no body.
      setLatest((await api.get<DataExportView | null>('/api/me/export')) ?? null);
    } catch {
      setLatest(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // The ready notification links to `/account#export`; the router does not scroll to a hash on its own.
  useEffect(() => {
    if (window.location.hash === '#export') {
      heading.current?.scrollIntoView();
    }
  }, []);

  useEffect(() => {
    if (latest?.status !== 'running') {
      return;
    }
    const timer = setTimeout(() => void load(), POLL_MS);
    return () => clearTimeout(timer);
  }, [latest, load]);

  const request = async () => {
    setError(null);
    try {
      setLatest(await api.post<DataExportView>('/api/me/export'));
    } catch (e) {
      const tooSoon = e instanceof ApiError && e.status === 429;
      setError(tooSoon ? t('account.exportTooSoon') : t('account.exportFailedToStart'));
    }
  };

  const nextAllowed = latest?.nextAllowedAt ? new Date(latest.nextAllowedAt) : null;
  const waiting = latest?.status !== 'failed' && nextAllowed != null && nextAllowed.getTime() > Date.now();

  return (
    <>
      <h2 id="export" ref={heading} className="mc-account__exportHeading">{t('account.exportHeading')}</h2>
      <div className="mc-account__export">
        <p>{t('account.exportBody')}</p>
        {latest?.status === 'running' && (
          <p className="mc-muted" role="status">
            {t('account.exportRunning')}
          </p>
        )}
        {latest?.status === 'ready' && latest.expiresAt && (
          <p>
            <a className="mc-btn mc-btn--accent" href={`/api/me/export/${latest.id}/download`} download>
              {t('account.exportDownload')}
            </a>{' '}
            <span className="mc-muted">
              {t('account.exportReady', {
                date: formatDate(latest.expiresAt, i18n.language),
                size: formatFileSize(latest.sizeBytes ?? 0),
              })}
            </span>
          </p>
        )}
        {latest?.status === 'failed' && <p className="mc-error">{t('account.exportFailed')}</p>}
        {latest?.status === 'expired' && <p className="mc-muted">{t('account.exportExpired')}</p>}
        {latest?.status !== 'running' &&
          (waiting && nextAllowed ? (
            <p className="mc-muted">
              {t('account.exportNext', { date: formatDate(nextAllowed.toISOString(), i18n.language) })}
            </p>
          ) : (
            <button type="button" className="mc-btn" onClick={() => void request()}>
              {t('account.exportRequest')}
            </button>
          ))}
        {error && <p className="mc-error">{error}</p>}
      </div>
    </>
  );
}
