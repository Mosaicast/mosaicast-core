// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import { problemMessage } from '../../api/problemMessage';
import type { ReachSeriesPoint, ReachSubjectTotal, ReachView } from '../../api/types';
import { SavedNote } from '../../a11y/SavedNote';
import { useUser } from '../../auth/UserContext';

type Range = '24h' | '7d' | '30d' | '365d';
const RANGE_HOURS: Record<Range, number> = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30, '365d': 24 * 365 };

/** One line of the chart: a kind from a source, keyed so each gets its own colour and legend entry. */
type LineKey = 'core-view' | 'core-play' | 'edge-view';
const LINES: LineKey[] = ['core-view', 'core-play', 'edge-view'];

/** Every bucket in the range, so a quiet hour is a zero on the line rather than a gap the line jumps. */
function buckets(view: ReachView): string[] {
  const step = view.granularity === 'day' ? 24 * 3600_000 : 3600_000;
  const start = new Date(view.from).getTime();
  const first = start - (start % step);
  const out: string[] = [];
  for (let t = first; t < new Date(view.to).getTime(); t += step) {
    out.push(new Date(t).toISOString());
  }
  return out;
}

function seriesOf(points: ReachSeriesPoint[], keys: string[]): Record<LineKey, number[]> {
  const index = new Map(keys.map((k, i) => [new Date(k).getTime(), i]));
  const out = Object.fromEntries(LINES.map((l) => [l, keys.map(() => 0)])) as Record<LineKey, number[]>;
  for (const p of points) {
    const line = `${p.source}-${p.kind}` as LineKey;
    const i = index.get(new Date(p.bucket).getTime());
    if (out[line] && i !== undefined) out[line][i] += p.count;
  }
  return out;
}

/**
 * A plain inline-SVG line chart: the shell carries no chart library (frontend rules), and three lines over
 * time need none. Colours are theme tokens, so it is right in light and dark.
 */
function Chart({ keys, lines, label }: { keys: string[]; lines: Record<LineKey, number[]>; label: string }) {
  const { t, i18n } = useTranslation();
  const width = 640;
  const height = 200;
  const pad = 28;
  /** Room for the top value's label on the left, which a fixed pad cut off once counts reached thousands. */
  const left = 56;
  const max = Math.max(1, ...LINES.flatMap((l) => lines[l]));
  const x = (i: number) => left + (keys.length <= 1 ? 0 : (i * (width - left - pad)) / (keys.length - 1));
  const y = (v: number) => height - pad - (v * (height - 2 * pad)) / max;
  return (
    <figure className="mc-reach__chart">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
        <line className="mc-reach__axis" x1={left} y1={height - pad} x2={width - pad} y2={height - pad} />
        <text className="mc-reach__tick" x={left - 6} y={y(max) + 4} textAnchor="end">
          {max.toLocaleString(i18n.language, { notation: 'compact' })}
        </text>
        <text className="mc-reach__tick" x={left - 6} y={height - pad + 4} textAnchor="end">
          0
        </text>
        {LINES.map((line) => (
          <polyline
            key={line}
            className={`mc-reach__line mc-reach__line--${line}`}
            points={lines[line].map((v, i) => `${x(i)},${y(v)}`).join(' ')}
          />
        ))}
      </svg>
      <figcaption className="mc-reach__legend">
        {LINES.map((line) => (
          <span key={line} className={`mc-reach__key mc-reach__key--${line}`}>
            {t(`admin.reach.line.${line}`)}
          </span>
        ))}
      </figcaption>
    </figure>
  );
}

function subjectLabel(subject: string): string {
  const slash = subject.indexOf('/');
  return slash < 0 ? subject : subject.slice(slash + 1);
}

/**
 * Admin → Reach (ARCHITECTURE §19): anonymous counts over a range — views and web-player plays from core,
 * and what the edge saw while the site was not open. Admins and podcasters read it; only admins switch
 * counting. The page says plainly what the numbers are not, so nobody mistakes them for visitors.
 */
export function AdminReach() {
  const { t, i18n } = useTranslation();
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';
  const [range, setRange] = useState<Range>('7d');
  const [view, setView] = useState<ReachView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    const to = new Date();
    to.setUTCMinutes(0, 0, 0);
    to.setUTCHours(to.getUTCHours() + 1);
    const from = new Date(to.getTime() - RANGE_HOURS[range] * 3600_000);
    api
      .get<ReachView>(`/api/admin/metrics?from=${from.toISOString()}&to=${to.toISOString()}`)
      .then((v) => {
        setView(v);
        setError(null);
      })
      .catch((e) => setError(problemMessage(e, t, t('admin.reach.loadFailed'))));
  }, [range, t]);

  const keys = useMemo(() => (view ? buckets(view) : []), [view]);
  const lines = useMemo(() => (view ? seriesOf(view.series, keys) : null), [view, keys]);
  const total = (line: LineKey) => (lines ? lines[line].reduce((a, b) => a + b, 0) : 0);
  const number = (n: number) => n.toLocaleString(i18n.language);
  const when = (iso: string) => new Date(iso).toLocaleString(i18n.language, { timeStyle: 'short' });

  const setCounting = async (counting: boolean) => {
    setSaved(false);
    try {
      await api.put('/api/admin/metrics/settings', { counting });
      setView((v) => (v ? { ...v, counting } : v));
      setSaved(true);
    } catch (e) {
      setError(problemMessage(e, t, t('admin.reach.saveFailed')));
    }
  };

  const top = (kind: ReachSubjectTotal['kind']) =>
    (view?.top ?? []).filter((r) => r.kind === kind && r.subject.startsWith('episode/')).slice(0, 10);

  return (
    <div className="mc-form mc-reach">
      <h2>{t('admin.reach.title')}</h2>
      <p className="mc-muted">{t('admin.reach.intro')}</p>
      {error && <p className="mc-error">{error}</p>}

      {isAdmin && view && (
        <label className="mc-toggle">
          <input type="checkbox" checked={view.counting} onChange={(e) => void setCounting(e.target.checked)} />
          <span>
            {t('admin.reach.counting')}
            <span className="mc-muted"> — {t('admin.reach.counting.hint')}</span>
          </span>
        </label>
      )}
      <SavedNote show={saved}>{t('admin.reach.saved')}</SavedNote>
      {view && !view.counting && <p className="mc-access__warning">{t('admin.reach.off')}</p>}

      <fieldset className="mc-options mc-reach__ranges">
        <legend>{t('admin.reach.range')}</legend>
        {(Object.keys(RANGE_HOURS) as Range[]).map((r) => (
          <label key={r} className="mc-toggle">
            <input type="radio" name="reach-range" checked={range === r} onChange={() => setRange(r)} />
            <span>{t(`admin.reach.range.${r}`)}</span>
          </label>
        ))}
      </fieldset>

      {view && lines && (
        <>
          <dl className="mc-reach__totals">
            {LINES.map((line) => (
              <div key={line}>
                <dt>{t(`admin.reach.line.${line}`)}</dt>
                <dd>{number(total(line))}</dd>
              </div>
            ))}
          </dl>
          <Chart keys={keys} lines={lines} label={t('admin.reach.chart')} />

          <div className="mc-reach__tables">
            {(['play', 'view'] as const).map((kind) => (
              <section key={kind}>
                <h3>{t(`admin.reach.top.${kind}`)}</h3>
                {top(kind).length === 0 ? (
                  <p className="mc-muted">{t('admin.reach.none')}</p>
                ) : (
                  <ol className="mc-reach__top">
                    {top(kind).map((row) => (
                      <li key={row.subject}>
                        <a href={`/episodes/${subjectLabel(row.subject)}`}>{subjectLabel(row.subject)}</a>
                        <span>{number(row.count)}</span>
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            ))}
          </div>

          <section className="mc-reach__edge">
            <h3>{t('admin.reach.edge.title')}</h3>
            {!view.edge.counting ? (
              <p className="mc-muted">{t('admin.reach.edge.off')}</p>
            ) : (
              <>
                <p>
                  {view.edge.waiting == null
                    ? t('admin.reach.edge.noReport')
                    : t('admin.reach.edge.waiting', {
                        count: view.edge.waiting,
                        n: view.edge.waitingCapped ? `${number(view.edge.waiting)}+` : number(view.edge.waiting),
                        page: t(`admin.reach.edge.page.${view.edge.page}`),
                        when: view.edge.waitingAt ? when(view.edge.waitingAt) : '',
                      })}
                </p>
                <p className="mc-muted">
                  {t('admin.reach.edge.peaks', {
                    today: number(view.edge.peakToday),
                    policy: number(view.edge.peakThisPolicy),
                  })}
                </p>
                {view.edgeViews.some((row) => row.count > 0) && (
                  <ul className="mc-reach__top">
                    {view.edgeViews.filter((row) => row.count > 0).map((row) => (
                      <li key={row.subject}>
                        <span>{t(`admin.reach.edge.page.${subjectLabel(row.subject)}`, subjectLabel(row.subject))}</span>
                        <span>{number(row.count)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </section>
        </>
      )}

      <p className="mc-muted mc-reach__notes">{t('admin.reach.notes')}</p>
    </div>
  );
}
