// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AiCrawlerPolicy, SeoView } from '../../api/types';

/**
 * SEO & crawlers admin (ARCHITECTURE §6.6, ADMIN only): the AI-crawler policy served in `robots.txt`.
 *
 * The spec is explicit that this is the operator's decision, not the platform's, so the panel presents the
 * choice without steering it — and says plainly that a `robots.txt` rule is a request rather than a control,
 * because a setting that reads like a lock and is not one is worse than no setting at all.
 */
export function AdminSeo() {
  const { t } = useTranslation();

  const [policy, setPolicy] = useState<AiCrawlerPolicy>('allow');
  const [blocked, setBlocked] = useState<string[]>([]);
  const [known, setKnown] = useState<SeoView['known']>([]);
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    api
      .get<SeoView>('/api/admin/seo')
      .then((seo) => {
        setPolicy(seo.policy);
        setBlocked(seo.blocked);
        setKnown(seo.known);
      })
      .catch(() => setFailed(true));
  }, []);

  const toggle = (agent: string) =>
    setBlocked((current) =>
      current.includes(agent) ? current.filter((a) => a !== agent) : [...current, agent],
    );

  const save = async () => {
    setSaved(false);
    const seo = await api.put<SeoView>('/api/admin/seo', { policy, blocked });
    setPolicy(seo.policy);
    setBlocked(seo.blocked);
    setSaved(true);
  };

  if (failed) {
    return <p className="mc-muted">{t('admin.seo.loadFailed')}</p>;
  }

  return (
    <div className="mc-form">
      <h2>{t('admin.seo.title')}</h2>
      <p className="mc-muted">{t('admin.seo.intro')}</p>

      <fieldset className="mc-options">
        <legend>{t('admin.seo.policy')}</legend>
        {(['allow', 'block', 'custom'] as const).map((value) => (
          <label key={value} className="mc-toggle">
            <input
              type="radio"
              name="ai-crawler-policy"
              value={value}
              checked={policy === value}
              onChange={() => setPolicy(value)}
            />
            <span>
              {t(`admin.seo.policy.${value}`)}
              <span className="mc-muted"> — {t(`admin.seo.policy.${value}.hint`)}</span>
            </span>
          </label>
        ))}
      </fieldset>

      {policy === 'custom' && (
        <fieldset className="mc-options">
          <legend>{t('admin.seo.blockList')}</legend>
          {known.map((crawler) => (
            <label key={crawler.agent} className="mc-toggle">
              <input
                type="checkbox"
                checked={blocked.includes(crawler.agent)}
                onChange={() => toggle(crawler.agent)}
              />
              <span>
                <code>{crawler.agent}</code>
                <span className="mc-muted">
                  {' '}
                  — {crawler.operator}, {crawler.purpose}
                </span>
              </span>
            </label>
          ))}
          <p className="mc-muted">{t('admin.seo.catalogNote')}</p>
        </fieldset>
      )}

      <p className="mc-muted">{t('admin.seo.notEnforced')}</p>

      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={save}>
          {t('admin.seo.save')}
        </button>
        {saved && <span className="mc-muted">{t('admin.seo.saved')}</span>}
        <a className="mc-btn" href="/robots.txt" target="_blank" rel="noreferrer">
          {t('admin.seo.preview')}
        </a>
      </div>
    </div>
  );
}
