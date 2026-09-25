// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { SlotRegion } from '../components/SlotRegion';
import { usePluginRegistry } from '../plugins/PluginRegistry';
import { NotFound } from './Placeholder';
import { useDocumentTitle } from '../a11y/documentTitle';

/**
 * A plugin's deep-link page (ARCHITECTURE §6.4). The host reserves `/p/{pluginId}/*` and hands the subpath
 * below it to the plugin as `ctx.route`, which is what makes plugin content — a wiki page, say — linkable
 * and shareable at all. The page renders the `page` region for that one plugin at site scope; the server
 * injects matching OpenGraph tags for the same URL via the plugin's `ShareMetadataProvider`.
 *
 * An unknown plugin, one that declares no `page` slot, or one an admin switched off (it drops out of the
 * public manifest) falls through to the shell's 404 — the server answers the same URL with a real 404 status.
 */
export function PluginPage() {
  const params = useParams();
  const pluginId = params.pluginId ?? '';
  const subpath = params['*'] ?? '';
  const { t } = useTranslation();
  const { plugins, status, reload } = usePluginRegistry();
  useDocumentTitle(status === 'failed' ? t('error.title') : plugins.find((p) => p.id === pluginId)?.name);

  // Not a 404 until the registry has actually answered: a deep link used to flash "Not found" while the
  // manifest was in flight, and keep it when that request failed (core#185).
  if (status === 'loading') {
    return (
      <section className="mc-page">
        <p className="mc-muted">{t('common.loading')}</p>
      </section>
    );
  }
  if (status === 'failed') {
    return (
      <section className="mc-page" role="alert">
        <h1 className="mc-page__title">{t('error.title')}</h1>
        <p className="mc-muted">{t('error.body')}</p>
        <button type="button" className="mc-btn" onClick={reload}>
          {t('error.retry')}
        </button>
      </section>
    );
  }

  const plugin = plugins.find((p) => p.id === pluginId);
  const hasPageSlot = plugin?.slots?.some((slot) => slot.placement === 'page') ?? false;
  if (!hasPageSlot) {
    return <NotFound />;
  }

  return <SlotRegion name="page" routePath={subpath} onlyPluginId={pluginId} />;
}
