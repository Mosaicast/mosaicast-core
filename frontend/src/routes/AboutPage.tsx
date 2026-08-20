// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { MOSAICAST_REPO_URL } from '../api/constants';
import { useMeta } from '../api/MetaContext';
import type { RenderedPage } from '../api/types';
import { ATTRIBUTIONS } from '../generated/attributions';
import { useResource } from '../hooks/useResource';
import { usePluginRegistry } from '../plugins/PluginRegistry';

/**
 * `/about` — what this is, who runs it, what it is running, and what it is built on.
 *
 * Four sections, each answering the question the one before it raises:
 *
 *  1. **This instance** — the operator's own words, authored through the legal mini-CMS under the `about`
 *     slug (§12.6) so it gets per-locale markdown and the existing admin editor for free. It comes first
 *     because a visitor arrived at *this podcast's site*, not at a piece of software: "whose site is this?"
 *     is the question they actually have, and answering with a paragraph about the platform first would be
 *     talking about ourselves. Absent if the operator deleted it, which is a legitimate choice, not an
 *     error — and then the page opens on Mosaicast instead.
 *  2. **What this is** — fixed text, translated, with this build's version and a link to the source. The
 *     AGPL obliges a network user to be able to reach the source; putting it here means that is one click
 *     from every page rather than a clause nobody can act on.
 *  3. **Plugins** — what this install actually runs, with whatever credit each plugin declared.
 *  4. **Built with** — everything Mosaicast stands on, generated from one source shared with
 *     `THIRD-PARTY-NOTICES.md` so the two cannot drift.
 *
 * Every section is independently absent-tolerant: a bare install with no plugins, no About text and no
 * legal pages still renders sections 1 and 4, because "what is this site?" is exactly the question a
 * visitor to a bare install is most likely to have.
 */
export function AboutPage() {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.slice(0, 2);
  const meta = useMeta();
  const { plugins } = usePluginRegistry();

  // A 404 here is the ordinary case, not a failure: the admin may have deleted the entry. `useResource`
  // surfaces it as an error we simply do not render.
  const { data: instance } = useResource<RenderedPage>(
    `/api/legal/about?locale=${encodeURIComponent(locale)}`,
  );

  const runtime = ATTRIBUTIONS.filter((a) => a.scope === 'runtime');
  const build = ATTRIBUTIONS.filter((a) => a.scope === 'build');

  return (
    <article className="mc-page mc-about">
      <h1 className="mc-page__title">{t('about.heading')}</h1>

      {instance && (
        <section className="mc-about__section">
          <h2>{instance.title}</h2>
          {/* Server-sanitized HTML (markdown → safe HTML in LegalService), same path as a legal page. */}
          <div className="mc-legal__body" dangerouslySetInnerHTML={{ __html: instance.html }} />
        </section>
      )}

      <section className="mc-about__section">
        <h2>{t('about.whatHeading')}</h2>
        <p>{t('about.whatBody')}</p>
        <p className="mc-muted">
          {t('about.openSource')}{' '}
          <a href={MOSAICAST_REPO_URL} target="_blank" rel="noreferrer noopener">
            {t('about.sourceLink')}
          </a>
          {meta?.version ? (
            <>
              {' · '}
              {t('about.version')} <code>{meta.version}</code>
            </>
          ) : null}
        </p>
      </section>

      <section className="mc-about__section">
        <h2>{t('about.pluginsHeading')}</h2>
        {plugins.length === 0 ? (
          <p className="mc-muted">{t('about.noPlugins')}</p>
        ) : (
          <>
            <p className="mc-muted">{t('about.pluginsBody')}</p>
            <div className="mc-tablewrap">
              <table className="mc-about__table">
                <thead>
                  <tr>
                    <th scope="col">{t('about.colName')}</th>
                    <th scope="col">{t('about.colVersion')}</th>
                    <th scope="col">{t('about.colLicense')}</th>
                    <th scope="col">{t('about.colAuthor')}</th>
                  </tr>
                </thead>
                <tbody>
                  {plugins.map((p) => (
                    <tr key={p.id}>
                      <th scope="row">
                        {p.homepage ? (
                          <a href={p.homepage} target="_blank" rel="noreferrer noopener">
                            {p.name}
                          </a>
                        ) : (
                          p.name
                        )}
                      </th>
                      <td>
                        <code>{p.version}</code>
                      </td>
                      {/* An em dash, not an empty cell: nothing declared is a fact worth showing plainly. */}
                      <td>{p.license ? <code>{p.license}</code> : <span className="mc-muted">—</span>}</td>
                      <td>
                        {p.author ?? <span className="mc-muted">—</span>}
                        {p.attribution && (
                          <>
                            {' '}
                            <a href={p.attribution} target="_blank" rel="noreferrer noopener">
                              {t('about.credits')}
                            </a>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>

      <section className="mc-about__section">
        <h2>{t('about.builtHeading')}</h2>
        <p className="mc-muted">{t('about.builtBody')}</p>
        <AttributionTable caption={t('about.shipsHeading')} rows={runtime} />
        <AttributionTable caption={t('about.buildHeading')} rows={build} />
      </section>
    </article>
  );
}

/** One scope's worth of credits. Licences render as `<code>` — they are identifiers, never translated. */
function AttributionTable({ caption, rows }: { caption: string; rows: readonly (typeof ATTRIBUTIONS)[number][] }) {
  const { t } = useTranslation();
  if (rows.length === 0) {
    return null;
  }
  return (
    <div className="mc-tablewrap">
      <table className="mc-about__table">
        <caption className="mc-about__caption">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('about.colProject')}</th>
            <th scope="col">{t('about.colLicense')}</th>
            <th scope="col">{t('about.colDoes')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.name}>
              <th scope="row">
                <a href={a.url} target="_blank" rel="noreferrer noopener">
                  {a.name}
                </a>
                {a.version && <span className="mc-muted"> {a.version}</span>}
              </th>
              <td>
                <code>{a.license}</code>
              </td>
              <td>{a.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
