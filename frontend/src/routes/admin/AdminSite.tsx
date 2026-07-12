// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { ModePolicy, SiteView } from '../../api/types';
import { applyTheme, resolveMode } from '../../theme/applyTheme';
import { useSite } from '../../theme/SiteContext';

const BRANDING_KEYS = ['logo', 'favicon', 'dark-logo'] as const;

/**
 * Site & branding admin (ARCHITECTURE §12.1/§12.2, ADMIN only): the site name, mode policy and accent seed
 * (saving applies the server-generated theme live), plus logo / favicon / dark-logo upload and clear.
 */
export function AdminSite() {
  const { t } = useTranslation();
  const { site } = useSite();

  const [siteName, setSiteName] = useState(site?.name ?? '');
  const [modePolicy, setModePolicy] = useState<ModePolicy>(site?.modePolicy ?? 'system');
  const [accentSeed, setAccentSeed] = useState(site?.accentSeed ?? '#c8553d');
  const [saved, setSaved] = useState(false);
  const [bust, setBust] = useState(0);

  const save = async () => {
    setSaved(false);
    const updated = await api.put<SiteView>('/api/admin/site', { siteName, accentSeed, modePolicy });
    applyTheme(updated.theme, resolveMode(updated.modePolicy)); // live preview of the generated theme
    setSaved(true);
  };

  const uploadAsset = async (key: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    await api.upload(`/api/admin/branding/${key}`, form);
    setBust(Date.now());
  };
  const clearAsset = async (key: string) => {
    await api.del(`/api/admin/branding/${key}`);
    setBust(Date.now());
  };

  return (
    <div className="mc-form">
      <h2>{t('admin.site.settings')}</h2>

      <label className="mc-field">
        <span>{t('admin.site.name')}</span>
        <input className="mc-input" type="text" value={siteName} onChange={(e) => setSiteName(e.target.value)} />
      </label>

      <label className="mc-field">
        <span>{t('admin.site.mode')}</span>
        <select value={modePolicy} onChange={(e) => setModePolicy(e.target.value as ModePolicy)}>
          <option value="light">{t('admin.site.light')}</option>
          <option value="dark">{t('admin.site.dark')}</option>
          <option value="system">{t('admin.site.system')}</option>
        </select>
      </label>

      <label className="mc-field">
        <span>{t('admin.site.accent')}</span>
        <input type="color" value={accentSeed} onChange={(e) => setAccentSeed(e.target.value)} />
        <code>{accentSeed}</code>
      </label>

      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={save}>
          {t('admin.site.save')}
        </button>
        {saved && <span className="mc-muted">{t('admin.site.saved')}</span>}
      </div>

      <h2>{t('admin.site.branding')}</h2>
      <div className="mc-branding">
        {BRANDING_KEYS.map((key) => (
          <div key={key} className="mc-branding__item">
            <span className="mc-branding__label">{key}</span>
            <div className="mc-branding__previews">
              <span className="mc-branding__swatch mc-branding__swatch--light">
                <img src={`/branding/${key}?b=${bust}`} alt="" />
              </span>
              <span className="mc-branding__swatch mc-branding__swatch--dark">
                <img src={`/branding/${key}?b=${bust}`} alt="" />
              </span>
            </div>
            <div className="mc-branding__actions">
              <label className="mc-btn">
                {t('admin.site.upload')}
                <input
                  type="file"
                  accept="image/*"
                  hidden
                  onChange={(e) => e.target.files?.[0] && uploadAsset(key, e.target.files[0])}
                />
              </label>
              <button type="button" className="mc-btn" onClick={() => clearAsset(key)}>
                {t('admin.site.clear')}
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
