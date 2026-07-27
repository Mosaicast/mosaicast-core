// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { AppLogEntry, AppLogFacets, HealthView, Paged } from '../../api/types';

/**
 * The admin log & health viewer (ARCHITECTURE §13). Everything the host knows about its own failures — a
 * rejected plugin, a feed that stopped polling, an unhandled 500, a plugin reporting its own trouble — used to
 * exist only in the container's stdout, so an operator without a terminal could not find out why something
 * stopped working. This is that information, filterable and readable in the browser.
 */

/** Minimum severity, not an exact match: WARN shows WARN and ERROR. '' shows everything. */
const LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG', ''] as const;
const PAGE_SIZE = 50;
const REFRESH_MS = 10_000;

interface Filters {
  level: string;
  subsystem: string;
  pluginId: string;
  q: string;
}

// The store keeps INFO (DEBUG in dev) so a failure's context survives; the viewer opens at WARN and above
// so an operator is not asked to read routine chatter to find the problem. Both are one dropdown apart.
const EMPTY_FILTERS: Filters = { level: 'WARN', subsystem: '', pluginId: '', q: '' };

function queryOf(filters: Filters, page: number): string {
  const params = new URLSearchParams();
  if (filters.level) params.set('level', filters.level);
  if (filters.subsystem) params.set('subsystem', filters.subsystem);
  if (filters.pluginId) params.set('pluginId', filters.pluginId);
  if (filters.q.trim()) params.set('q', filters.q.trim());
  params.set('page', String(page));
  params.set('size', String(PAGE_SIZE));
  return params.toString();
}

export function AdminLogs() {
  const { t } = useTranslation();
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Paged<AppLogEntry> | null>(null);
  const [facets, setFacets] = useState<AppLogFacets>({ subsystems: [], pluginIds: [] });
  const [health, setHealth] = useState<HealthView | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Kept in a ref so the refresh interval never has to be town down and rebuilt as filters change.
  const requestRef = useRef({ filters, page });
  requestRef.current = { filters, page };

  const load = useCallback(async () => {
    try {
      const current = requestRef.current;
      const [entries, health] = await Promise.all([
        api.get<Paged<AppLogEntry>>(`/api/admin/logs?${queryOf(current.filters, current.page)}`),
        api.get<HealthView>('/api/admin/health'),
      ]);
      setResult(entries);
      setHealth(health);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : String(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load, filters, page]);

  useEffect(() => {
    api
      .get<AppLogFacets>('/api/admin/logs/facets')
      .then(setFacets)
      .catch(() => setFacets({ subsystems: [], pluginIds: [] }));
  }, [result?.totalElements]);

  useEffect(() => {
    // Paused while a row is open: re-rendering the table under the reader would collapse what they opened.
    if (!autoRefresh || expanded != null) {
      return;
    }
    const handle = window.setInterval(() => void load(), REFRESH_MS);
    return () => window.clearInterval(handle);
  }, [autoRefresh, expanded, load]);

  const update = (patch: Partial<Filters>) => {
    setFilters((current) => ({ ...current, ...patch }));
    setPage(0);
    setExpanded(null);
  };

  return (
    <div className="mc-form">
      <h2>{t('admin.logs.title')}</h2>

      {health && <HealthCard health={health} />}
      {error && <p className="mc-error">{error}</p>}

      <div className="mc-logfilters">
        <label className="mc-field mc-field--inline">
          <span>{t('admin.logs.level')}</span>
          <select value={filters.level} onChange={(e) => update({ level: e.target.value })}>
            {LEVELS.map((level) => (
              <option key={level} value={level}>
                {level ? t('admin.logs.levelAtLeast', { level }) : t('admin.logs.anyLevel')}
              </option>
            ))}
          </select>
        </label>
        <label className="mc-field mc-field--inline">
          <span>{t('admin.logs.subsystem')}</span>
          <select value={filters.subsystem} onChange={(e) => update({ subsystem: e.target.value })}>
            <option value="">{t('admin.logs.anySubsystem')}</option>
            {facets.subsystems.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        {facets.pluginIds.length > 0 && (
          <label className="mc-field mc-field--inline">
            <span>{t('admin.logs.plugin')}</span>
            <select value={filters.pluginId} onChange={(e) => update({ pluginId: e.target.value })}>
              <option value="">{t('admin.logs.anyPlugin')}</option>
              {facets.pluginIds.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="mc-field mc-field--inline">
          <span>{t('admin.logs.search')}</span>
          <input
            className="mc-input"
            type="search"
            value={filters.q}
            onChange={(e) => update({ q: e.target.value })}
            placeholder={t('admin.logs.searchPlaceholder')}
          />
        </label>
        <label className="mc-toggle">
          <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
          {t('admin.logs.autoRefresh')}
        </label>
        <button type="button" className="mc-btn" onClick={() => void load()}>
          {t('admin.logs.refresh')}
        </button>
      </div>

      {result == null ? (
        <p className="mc-muted">{t('common.loading')}</p>
      ) : result.items.length === 0 ? (
        <p className="mc-muted">{t('admin.logs.empty')}</p>
      ) : (
        <ul className="mc-list mc-loglist">
          {result.items.map((entry) => (
            <li key={entry.id} className="mc-logrow">
              <button
                type="button"
                className="mc-logrow__head"
                onClick={() => setExpanded(expanded === entry.id ? null : entry.id)}
              >
                <span className="mc-muted mc-logrow__at">{new Date(entry.at).toLocaleString()}</span>
                <span className={`mc-chip mc-chip--${entry.level.toLowerCase()}`}>{entry.level}</span>
                <span className="mc-muted mc-logrow__origin">
                  {entry.subsystem}
                  {entry.pluginId ? ` · ${entry.pluginId}` : ''}
                  {entry.source ? ` · ${entry.source}` : ''}
                </span>
                <span className="mc-logrow__message">{entry.message}</span>
              </button>
              {expanded === entry.id && (entry.detail || entry.context) && (
                <div className="mc-logrow__detail">
                  {entry.context && <pre>{JSON.stringify(entry.context, null, 2)}</pre>}
                  {entry.detail && <pre>{entry.detail}</pre>}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {result != null && result.totalPages > 1 && (
        <div className="mc-form__actions">
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

/** The "is anything broken right now?" summary above the log. */
function HealthCard({ health }: { health: HealthView }) {
  const { t } = useTranslation();
  const brokenPlugins = health.plugins.filter((p) => p.status === 'REJECTED' || !p.enabled);
  const brokenFeeds = health.feeds.filter((f) => f.consecutiveFailures > 0 || f.lastError);
  const errors = health.counts.filter((c) => c.level === 'ERROR').reduce((sum, c) => sum + c.count, 0);
  const warnings = health.counts.filter((c) => c.level === 'WARN').reduce((sum, c) => sum + c.count, 0);
  const healthy = brokenPlugins.length === 0 && brokenFeeds.length === 0 && errors === 0;

  return (
    <div className={`mc-health${healthy ? ' mc-health--ok' : ''}`}>
      <div className="mc-health__summary">
        <strong>{healthy ? t('admin.logs.healthOk') : t('admin.logs.healthAttention')}</strong>
        <span className="mc-muted">
          {t('admin.logs.last24h', { errors, warnings })} · {t('admin.logs.version', { version: health.version })}
        </span>
      </div>
      {brokenPlugins.length > 0 && (
        <ul className="mc-health__list">
          {brokenPlugins.map((plugin) => (
            <li key={plugin.id}>
              <strong>{plugin.name ?? plugin.id}</strong>{' '}
              <span className="mc-muted">
                {plugin.status === 'REJECTED'
                  ? (plugin.reason ?? t('admin.plugins.rejected'))
                  : t('admin.plugins.disabled')}
              </span>
            </li>
          ))}
        </ul>
      )}
      {brokenFeeds.length > 0 && (
        <ul className="mc-health__list">
          {brokenFeeds.map((feed) => (
            <li key={feed.id}>
              <strong>{feed.title}</strong>{' '}
              <span className="mc-muted">
                {feed.lastError ?? feed.lastFetchStatus}
                {feed.consecutiveFailures > 0 ? ` (${feed.consecutiveFailures}×)` : ''}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
