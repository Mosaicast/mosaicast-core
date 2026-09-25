// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api, ApiError } from '../../api/client';
import { problemMessage } from '../../api/problemMessage';
import type { AdminFeed, FeedPreview, Suggestion } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Icon } from '../../components/Icon';

/** Poll-interval presets (seconds): 15 min / 30 min / 1 h / 6 h / 24 h. */
const INTERVAL_PRESETS = [900, 1800, 3600, 21600, 86400];

function intervalLabel(seconds: number): string {
  return seconds % 3600 === 0 ? `${seconds / 3600} h` : `${Math.round(seconds / 60)} min`;
}

/**
 * Feed management (ARCHITECTURE §5, PODCASTER+): list feeds with poll state, enable/disable, refresh, set the
 * poll interval, add a feed (preview → create), and review the fuzzy PLANNED-binding suggestions (M3 §5.3).
 */
export function AdminFeeds() {
  const { t } = useTranslation();
  const [feeds, setFeeds] = useState<AdminFeed[]>([]);
  const [url, setUrl] = useState('');
  const [preview, setPreview] = useState<FeedPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<AdminFeed | null>(null);
  const [openSuggestions, setOpenSuggestions] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);

  const load = () => api.get<AdminFeed[]>('/api/admin/feeds').then(setFeeds).catch(() => {});
  useEffect(() => {
    void load();
  }, []);

  const doPreview = async () => {
    setError(null);
    // A new preview replaces the old one, whatever it finds: a failed lookup used to leave the previous
    // success on screen beside the error, its "Add" button still live for the old URL (#199).
    setPreview(null);
    try {
      setPreview(await api.post<FeedPreview>('/api/admin/feeds/preview', { url: url.trim() }));
    } catch (e) {
      setError(problemMessage(e, t, t('admin.feeds.previewFailed')));
    }
  };
  const doAdd = async () => {
    setError(null);
    try {
      await api.post('/api/admin/feeds', { url: url.trim(), title: preview?.title ?? '' });
      setUrl('');
      setPreview(null);
      await load();
    } catch (e) {
      setError(problemMessage(e, t, t('admin.feeds.addFailed')));
    }
  };
  const removeFeed = async (feed: AdminFeed) => {
    setError(null);
    try {
      await api.del(
        `/api/admin/feeds/${feed.id}?confirm=${encodeURIComponent(feed.slug ?? feed.id)}`,
      );
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : String(e));
    }
  };

  const toggle = async (feed: AdminFeed) => {
    await api.post(`/api/admin/feeds/${feed.id}/enabled?value=${!feed.enabled}`);
    await load();
  };
  const refresh = async (feed: AdminFeed) => {
    await api.post(`/api/admin/feeds/${feed.id}/refresh`);
    await load();
  };
  const setInterval = async (feed: AdminFeed, seconds: number) => {
    await api.post(`/api/admin/feeds/${feed.id}/poll-interval?seconds=${seconds}`);
    await load();
  };
  const showSuggestions = async (feedId: string) => {
    if (openSuggestions === feedId) {
      setOpenSuggestions(null);
      return;
    }
    setOpenSuggestions(feedId);
    setSuggestions(await api.get<Suggestion[]>(`/api/admin/feeds/${feedId}/suggestions`).catch(() => []));
  };
  const confirmSuggestion = async (id: string, feedId: string) => {
    await api.post(`/api/admin/feeds/suggestions/${id}/confirm`);
    setSuggestions(await api.get<Suggestion[]>(`/api/admin/feeds/${feedId}/suggestions`).catch(() => []));
    await load();
  };
  const dismissSuggestion = async (id: string, feedId: string) => {
    await api.del(`/api/admin/feeds/suggestions/${id}`);
    setSuggestions(await api.get<Suggestion[]>(`/api/admin/feeds/${feedId}/suggestions`).catch(() => []));
  };

  return (
    <div className="mc-form">
      <h2>{t('admin.feeds.add')}</h2>
      {error && <p className="mc-error">{error}</p>}
      {deleting && (
        <ConfirmDialog
          title={t('admin.feeds.delete')}
          // Spells out what goes and what stays, because "delete this feed" does not convey that every
          // episode, everyone's listening position in them, and what plugins stored about them go too.
          body={t('admin.feeds.deleteConfirm', {
            title: deleting.title,
            count: deleting.episodeCount,
          })}
          confirmLabel={t('admin.feeds.delete')}
          confirmWord={deleting.slug ?? deleting.id}
          onConfirm={() => {
            const target = deleting;
            setDeleting(null);
            void removeFeed(target);
          }}
          onCancel={() => setDeleting(null)}
        />
      )}
      <div className="mc-feedadd">
        {/* A real label: the placeholder was the field's only name, and it disappears at the first keystroke
            (#200). Visually hidden, because the section heading above already says what this is. */}
        <label className="mc-sr-only" htmlFor="mc-feedadd-url">
          {t('admin.feeds.url')}
        </label>
        <input
          id="mc-feedadd-url"
          className="mc-input"
          type="url"
          placeholder="https://…/feed.xml"
          value={url}
          onChange={(e) => {
            setUrl(e.target.value);
            // The preview belongs to the URL it was made for.
            setPreview(null);
          }}
        />
        <button type="button" className="mc-btn" disabled={!url.trim()} onClick={doPreview}>
          {t('admin.feeds.preview')}
        </button>
      </div>
      {preview && (
        <div className="mc-preview">
          <strong>{preview.title}</strong> · {t('feed.episodeCount', { count: preview.episodeCount })}
          <ul>
            {(preview.sampleTitles ?? []).slice(0, 5).map((title) => (
              <li key={title} className="mc-muted">
                {title}
              </li>
            ))}
          </ul>
          <button type="button" className="mc-btn mc-btn--accent" onClick={doAdd}>
            {t('admin.feeds.confirmAdd')}
          </button>
        </div>
      )}

      <h2>{t('admin.feeds.list')}</h2>
      <ul className="mc-list">
        {feeds.map((feed) => (
          <li key={feed.id} className="mc-feedrow">
            <div className="mc-feedrow__main">
              <div>
                <strong>{feed.title}</strong>
                <div className="mc-muted mc-feedrow__url">{feed.url}</div>
                <div className="mc-muted">
                  {t('feed.episodeCount', { count: feed.episodeCount })} ·{' '}
                  {/* Through the catalog: `NOT_MODIFIED` is an HTTP status name, not something to show (#192). */}
                  {t(`admin.feeds.status.${feed.lastFetchStatus ?? 'NEVER'}`, {
                    defaultValue: feed.lastFetchStatus ?? '—',
                  })}
                  {feed.consecutiveFailures > 0 && (
                    <>
                      {' · '}
                      <Icon name="warning" label={t('admin.feeds.failures')} /> {feed.consecutiveFailures}
                    </>
                  )}
                </div>
                {/* The error text was fetched and typed all along, and never shown — so a failing feed
                    looked like a bare status word with no way to find out why. */}
                {feed.lastError && <p className="mc-error">{feed.lastError}</p>}
              </div>
              <div className="mc-feedrow__actions">
                <label className="mc-toggle">
                  <input type="checkbox" checked={feed.enabled} onChange={() => toggle(feed)} />
                  {t('admin.feeds.enabled')}
                </label>
                <label className="mc-feedrow__interval">
                  <span className="mc-muted">{t('admin.feeds.interval')}</span>
                  <select
                    value={feed.pollIntervalSeconds}
                    onChange={(e) => setInterval(feed, Number(e.target.value))}
                  >
                    {(INTERVAL_PRESETS.includes(feed.pollIntervalSeconds)
                      ? INTERVAL_PRESETS
                      : [feed.pollIntervalSeconds, ...INTERVAL_PRESETS]
                    ).map((s) => (
                      <option key={s} value={s}>
                        {intervalLabel(s)}
                      </option>
                    ))}
                  </select>
                </label>
                <button type="button" className="mc-btn" onClick={() => refresh(feed)}>
                  {t('admin.feeds.refresh')}
                </button>
                <button type="button" className="mc-btn" onClick={() => showSuggestions(feed.id)}>
                  {t('admin.feeds.suggestions')}
                </button>
                <button type="button" className="mc-btn mc-btn--danger" onClick={() => setDeleting(feed)}>
                  {t('common.delete')}
                </button>
              </div>
            </div>
            {openSuggestions === feed.id && (
              <div className="mc-suggestions">
                {suggestions.length === 0 && <p className="mc-muted">{t('admin.feeds.noSuggestions')}</p>}
                {suggestions.map((s) => (
                  <div key={s.id} className="mc-suggestion">
                    <span>
                      <strong>{s.plannedTitle}</strong> <Icon name="swap" /> {s.rawTitle}{' '}
                      <span className="mc-muted">({Math.round(s.similarity * 100)}%)</span>
                    </span>
                    <span className="mc-suggestion__actions">
                      <button type="button" className="mc-btn mc-btn--accent" onClick={() => confirmSuggestion(s.id, feed.id)}>
                        {t('admin.feeds.confirm')}
                      </button>
                      <button type="button" className="mc-btn" onClick={() => dismissSuggestion(s.id, feed.id)}>
                        {t('admin.feeds.dismiss')}
                      </button>
                    </span>
                  </div>
                ))}
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
