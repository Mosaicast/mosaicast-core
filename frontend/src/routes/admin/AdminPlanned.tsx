// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../../api/client';
import { problemMessage } from '../../api/problemMessage';
import type { AdminFeed, MatchCandidate, PlannedEpisode } from '../../api/types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { formatPublishedDate, formatSeasonEpisode } from '../../util/format';

type Visibility = 'quiet' | 'now' | 'at';

/** What the form holds; numbers stay strings until sent, so an empty field means "none". */
interface Draft {
  season: string;
  episodeNo: string;
  title: string;
  description: string;
  visibility: Visibility;
  /** A `datetime-local` value, in the admin's own time zone. */
  at: string;
}

const EMPTY: Draft = { season: '', episodeNo: '', title: '', description: '', visibility: 'quiet', at: '' };

const toNumber = (value: string) => (value.trim() === '' ? null : Number(value));

/** The API's `announceAt`: null keeps it quiet, "now" announces it, otherwise an instant from local time. */
function announceAtOf(draft: Draft): string | null {
  if (draft.visibility === 'quiet') return null;
  if (draft.visibility === 'now' || !draft.at) return 'now';
  return new Date(draft.at).toISOString();
}

/** An instant as a `datetime-local` value in the admin's time zone. */
function toLocalInput(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function draftOf(plan: PlannedEpisode): Draft {
  const scheduled = plan.announceAt != null && plan.phase === 'PLANNED';
  return {
    season: plan.season == null ? '' : String(plan.season),
    episodeNo: plan.episodeNo == null ? '' : String(plan.episodeNo),
    title: plan.title ?? '',
    description: plan.description ?? '',
    visibility: plan.phase === 'UPCOMING' ? 'now' : scheduled ? 'at' : 'quiet',
    at: scheduled && plan.announceAt ? toLocalInput(plan.announceAt) : '',
  };
}

/** The shared fields of the plan and edit forms. */
function PlanFields({ draft, onChange, idPrefix }: { draft: Draft; onChange: (d: Draft) => void; idPrefix: string }) {
  const { t } = useTranslation();
  const set = (patch: Partial<Draft>) => onChange({ ...draft, ...patch });
  return (
    <>
      <div className="mc-planned__numbers">
        <label className="mc-field mc-field--inline">
          <span>{t('admin.planned.season')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={0}
            value={draft.season}
            onChange={(e) => set({ season: e.target.value })}
          />
        </label>
        <label className="mc-field mc-field--inline">
          <span>{t('admin.planned.episodeNo')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={0}
            value={draft.episodeNo}
            onChange={(e) => set({ episodeNo: e.target.value })}
          />
        </label>
      </div>
      <label className="mc-field">
        <span>{t('admin.planned.titleField')}</span>
        <input
          className="mc-input"
          type="text"
          maxLength={200}
          required
          value={draft.title}
          onChange={(e) => set({ title: e.target.value })}
        />
      </label>
      <label className="mc-field">
        <span>{t('admin.planned.description')}</span>
        <textarea
          className="mc-textarea"
          rows={3}
          value={draft.description}
          onChange={(e) => set({ description: e.target.value })}
        />
      </label>
      <fieldset className="mc-options">
        <legend>{t('admin.planned.visibility')}</legend>
        {(['quiet', 'now', 'at'] as const).map((value) => (
          <label key={value} className="mc-toggle">
            <input
              type="radio"
              name={`${idPrefix}-visibility`}
              checked={draft.visibility === value}
              onChange={() => set({ visibility: value })}
            />
            <span>
              {t(`admin.planned.visibility.${value}`)}
              <span className="mc-muted"> — {t(`admin.planned.visibility.${value}.hint`)}</span>
            </span>
          </label>
        ))}
        {draft.visibility === 'at' && (
          <label className="mc-field mc-field--inline">
            <span>{t('admin.planned.announceAt')}</span>
            <input
              className="mc-input"
              type="datetime-local"
              value={draft.at}
              onChange={(e) => set({ at: e.target.value })}
            />
          </label>
        )}
      </fieldset>
    </>
  );
}

/** A plan's phase as a chip: quiet, scheduled for a date, or upcoming. */
function PhaseChip({ plan }: { plan: PlannedEpisode }) {
  const { t, i18n } = useTranslation();
  if (plan.phase === 'UPCOMING') {
    return <span className="mc-chip">{t('card.upcoming')}</span>;
  }
  if (plan.announceAt) {
    return (
      <span className="mc-chip mc-chip--quiet">
        {t('admin.planned.scheduled', { date: formatPublishedDate(plan.announceAt, i18n.language) })}
      </span>
    );
  }
  return <span className="mc-chip mc-chip--quiet">{t('episode.notAnnounced')}</span>;
}

/**
 * Planning episodes (ARCHITECTURE §4.3, core#252), for podcasters and admins: plan one before its feed item
 * exists — quietly, public now, or public at a set time — then edit, announce, cancel, or match it to an
 * episode the feed imported separately when neither the numbers nor the title found the pair.
 */
export function AdminPlanned() {
  const { t } = useTranslation();
  const [feeds, setFeeds] = useState<AdminFeed[]>([]);
  const [feedId, setFeedId] = useState('');
  const [plans, setPlans] = useState<PlannedEpisode[]>([]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<PlannedEpisode | null>(null);
  const [editing, setEditing] = useState<{ slug: string; draft: Draft } | null>(null);
  const [cancelling, setCancelling] = useState<PlannedEpisode | null>(null);
  const [matching, setMatching] = useState<{ slug: string; candidates: MatchCandidate[]; choice: string } | null>(
    null,
  );

  const load = () =>
    api
      .get<PlannedEpisode[]>('/api/admin/episodes/planned')
      .then(setPlans)
      .catch((e) => setError(problemMessage(e, t, t('admin.planned.loadFailed'))));

  useEffect(() => {
    api
      .get<AdminFeed[]>('/api/admin/feeds')
      .then((list) => {
        setFeeds(list);
        setFeedId((current) => current || list[0]?.id || '');
      })
      .catch(() => {});
    void load();
    // `t` is stable per language; reloading on a language switch would be pointless work.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async (action: () => Promise<unknown>, fallback: string) => {
    setError(null);
    try {
      await action();
      await load();
      return true;
    } catch (e) {
      setError(problemMessage(e, t, fallback));
      return false;
    }
  };

  const plan = async () => {
    const ok = await run(async () => {
      const made = await api.post<PlannedEpisode>(`/api/admin/feeds/${feedId}/planned-episodes`, {
        season: toNumber(draft.season),
        episodeNo: toNumber(draft.episodeNo),
        title: draft.title.trim(),
        description: draft.description,
        announceAt: announceAtOf(draft),
      });
      setCreated(made);
    }, t('admin.planned.createFailed'));
    if (ok) setDraft(EMPTY);
  };

  const saveEdit = async () => {
    if (!editing) return;
    const ok = await run(
      () =>
        api.patch(`/api/admin/episodes/${editing.slug}`, {
          season: toNumber(editing.draft.season),
          episodeNo: toNumber(editing.draft.episodeNo),
          title: editing.draft.title.trim(),
          description: editing.draft.description,
          announceAt: announceAtOf(editing.draft),
        }),
      t('admin.planned.saveFailed'),
    );
    if (ok) setEditing(null);
  };

  const openMatch = async (slug: string) => {
    setError(null);
    try {
      const candidates = await api.get<MatchCandidate[]>(`/api/admin/episodes/${slug}/match-candidates`);
      setMatching({ slug, candidates, choice: '' });
    } catch (e) {
      setError(problemMessage(e, t, t('admin.planned.loadFailed')));
    }
  };

  const feedTitle = (plan: PlannedEpisode) => plan.feedTitle ?? plan.feedSlug ?? '';

  return (
    <div className="mc-form">
      <h2>{t('admin.planned.title')}</h2>
      <p className="mc-muted">{t('admin.planned.intro')}</p>
      {error && <p className="mc-error">{error}</p>}

      <section className="mc-planned__create" aria-labelledby="mc-planned-create">
        <h3 id="mc-planned-create">{t('admin.planned.create')}</h3>
        {feeds.length > 1 && (
          <label className="mc-field mc-field--inline">
            <span>{t('admin.planned.feed')}</span>
            <select value={feedId} onChange={(e) => setFeedId(e.target.value)}>
              {feeds.map((feed) => (
                <option key={feed.id} value={feed.id}>
                  {feed.title}
                </option>
              ))}
            </select>
          </label>
        )}
        <PlanFields draft={draft} onChange={setDraft} idPrefix="mc-planned-new" />
        <div className="mc-form__actions">
          <button
            type="button"
            className="mc-btn mc-btn--accent"
            disabled={!draft.title.trim() || !feedId || (draft.visibility === 'at' && !draft.at)}
            onClick={plan}
          >
            {t('admin.planned.submit')}
          </button>
        </div>
        {created && (
          <p className="mc-muted" role="status">
            {t('admin.planned.created')} <Link to={created.url}>{created.title}</Link> — <code>{created.slug}</code>
          </p>
        )}
      </section>

      <h3>{t('admin.planned.list')}</h3>
      {plans.length === 0 && <p className="mc-muted">{t('admin.planned.none')}</p>}
      <ul className="mc-list">
        {plans.map((plan) => (
          <li key={plan.slug} className="mc-feedrow">
            <div className="mc-feedrow__main">
              <div>
                <strong>{plan.title}</strong>
                <div className="mc-muted">
                  {feeds.length > 1 && <>{feedTitle(plan)} · </>}
                  {formatSeasonEpisode(plan.season, plan.episodeNo) && (
                    <>{formatSeasonEpisode(plan.season, plan.episodeNo)} · </>
                  )}
                  <code>{plan.slug}</code>
                </div>
                <PhaseChip plan={plan} />
              </div>
              <div className="mc-feedrow__actions">
                <Link className="mc-btn" to={plan.url}>
                  {t('admin.planned.open')}
                </Link>
                <button
                  type="button"
                  className="mc-btn"
                  onClick={() => setEditing(editing?.slug === plan.slug ? null : { slug: plan.slug, draft: draftOf(plan) })}
                >
                  {editing?.slug === plan.slug ? t('common.close') : t('common.edit')}
                </button>
                {plan.phase === 'PLANNED' && (
                  <button
                    type="button"
                    className="mc-btn"
                    onClick={() => run(() => api.post(`/api/admin/episodes/${plan.slug}/announce`), t('admin.planned.saveFailed'))}
                  >
                    {t('admin.planned.announce')}
                  </button>
                )}
                <button type="button" className="mc-btn" onClick={() => openMatch(plan.slug)}>
                  {t('admin.planned.match')}
                </button>
                <button type="button" className="mc-btn mc-btn--danger" onClick={() => setCancelling(plan)}>
                  {t('admin.planned.cancel')}
                </button>
              </div>
            </div>

            {editing?.slug === plan.slug && (
              <div className="mc-planned__edit">
                <PlanFields
                  draft={editing.draft}
                  onChange={(d) => setEditing({ slug: plan.slug, draft: d })}
                  idPrefix={`mc-planned-${plan.slug}`}
                />
                <div className="mc-form__actions">
                  <button
                    type="button"
                    className="mc-btn mc-btn--accent"
                    disabled={!editing.draft.title.trim() || (editing.draft.visibility === 'at' && !editing.draft.at)}
                    onClick={saveEdit}
                  >
                    {t('common.save')}
                  </button>
                </div>
              </div>
            )}

            {matching?.slug === plan.slug && (
              <fieldset className="mc-options mc-planned__match">
                <legend>{t('admin.planned.matchTitle')}</legend>
                <p className="mc-muted">{t('admin.planned.matchIntro')}</p>
                {matching.candidates.length === 0 && <p className="mc-muted">{t('admin.planned.noCandidates')}</p>}
                {matching.candidates.map((c) => (
                  <label key={c.slug} className="mc-toggle">
                    <input
                      type="radio"
                      name={`mc-match-${plan.slug}`}
                      disabled={c.hasPluginData}
                      checked={matching.choice === c.slug}
                      onChange={() => setMatching({ ...matching, choice: c.slug })}
                    />
                    <span>
                      {c.title}
                      <span className="mc-muted">
                        {formatSeasonEpisode(c.season, c.episodeNo) && <> · {formatSeasonEpisode(c.season, c.episodeNo)}</>}
                        {c.hasPluginData && <> — {t('admin.planned.candidateHasData')}</>}
                      </span>
                    </span>
                  </label>
                ))}
                <div className="mc-form__actions">
                  <button
                    type="button"
                    className="mc-btn mc-btn--accent"
                    disabled={!matching.choice}
                    onClick={async () => {
                      const ok = await run(
                        () => api.post(`/api/admin/episodes/${plan.slug}/match`, { episode: matching.choice }),
                        t('admin.planned.matchFailed'),
                      );
                      if (ok) setMatching(null);
                    }}
                  >
                    {t('admin.planned.matchConfirm')}
                  </button>
                  <button type="button" className="mc-btn" onClick={() => setMatching(null)}>
                    {t('common.close')}
                  </button>
                </div>
              </fieldset>
            )}
          </li>
        ))}
      </ul>

      {cancelling && (
        <ConfirmDialog
          title={t('admin.planned.cancel')}
          body={t('admin.planned.cancelConfirm', { title: cancelling.title })}
          confirmLabel={t('admin.planned.cancel')}
          confirmWord={cancelling.slug}
          onConfirm={() => {
            const slug = cancelling.slug;
            setCancelling(null);
            void run(() => api.del(`/api/admin/episodes/${slug}`), t('admin.planned.saveFailed'));
          }}
          onCancel={() => setCancelling(null)}
        />
      )}
    </div>
  );
}
