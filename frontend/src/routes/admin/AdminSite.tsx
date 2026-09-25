// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import { contrastRatio } from '../../theme/contrast';
import type { ModePolicy, SiteView } from '../../api/types';
import { useSite } from '../../theme/SiteContext';
import { SavedNote } from '../../a11y/SavedNote';

const BRANDING_KEYS = ['logo', 'favicon', 'dark-logo'] as const;

/**
 * Site & branding admin (ARCHITECTURE §12.1/§12.2, ADMIN only): the site name, mode policy and accent seed
 * (saving applies the server-generated theme live), plus logo / favicon / dark-logo upload and clear.
 *
 * Languages live on their own page (§12.7): the default language has to be checked against the languages
 * content may be authored in, and that check belongs where the lists are edited.
 */
export function AdminSite() {
  const { t } = useTranslation();
  const { site, refresh } = useSite();

  const [siteName, setSiteName] = useState(site?.name ?? '');
  const [modePolicy, setModePolicy] = useState<ModePolicy>(site?.modePolicy ?? 'system');
  const [accentSeed, setAccentSeed] = useState(site?.accentSeed ?? '#c8553d');
  const [saved, setSaved] = useState(false);
  const [bust, setBust] = useState(0);
  const [uploadError, setUploadError] = useState<{ key: string; message: string } | null>(null);

  // Prefill from the loaded site config (the context is null on the first render, so `useState`'s initial
  // values above miss the real values — sync them here once `site` arrives).
  useEffect(() => {
    if (site) {
      setSiteName(site.name);
      setModePolicy(site.modePolicy);
      setAccentSeed(site.accentSeed);
    }
  }, [site]);

  const save = async () => {
    setSaved(false);
    await api.put<SiteView>('/api/admin/site', { siteName, accentSeed, modePolicy });
    // Refresh the shared site payload so the whole shell (and this form) reflects the saved theme live.
    await refresh();
    setSaved(true);
  };

  const uploadAsset = async (key: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    setUploadError(null);
    try {
      await api.upload(`/api/admin/branding/${key}`, form);
      setBust(Date.now());
    } catch (err) {
      // The server says why — too large, or not a raster image — and only the person who picked the file can
      // act on that. It used to be an unhandled rejection with nothing on the page.
      setUploadError({ key, message: err instanceof Error ? err.message : String(err) });
    }
  };

  // How the accent reads as text on the current backgrounds (core#162). The server clamps a shade of it for
  // links and the focus ring either way; this tells the admin, while choosing, that the colour they see is
  // not the one links will be.
  //
  // Measured on the light background only: that is where the seed is used as it is. Dark mode never shows the
  // raw seed — the generator lifts it first — so a warning computed from it there would be about a colour
  // nobody sees.
  const accentRatio = site ? contrastRatio(accentSeed, site.theme.light.bg) : null;
  const accentHardToRead = accentRatio != null && accentRatio < 4.5;
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
        <span className="mc-colorpick">
          <input
            className="mc-colorpick__input"
            type="color"
            value={accentSeed}
            onChange={(e) => setAccentSeed(e.target.value)}
          />
          <code>{accentSeed}</code>
        </span>
      </label>
      {accentHardToRead && (
        <p className="mc-muted" role="status">
          {t('admin.site.accentContrast')}
        </p>
      )}

      <div className="mc-form__actions">
        <button type="button" className="mc-btn mc-btn--accent" onClick={save}>
          {t('admin.site.save')}
        </button>
        <SavedNote show={saved}>{t('admin.site.saved')}</SavedNote>
      </div>

      <h2>{t('admin.site.branding')}</h2>
      <p className="mc-muted" id="mc-branding-hint">
        {t('admin.site.uploadHint')}
      </p>
      <div className="mc-branding">
        {BRANDING_KEYS.map((key) => (
          <div key={key} className="mc-branding__item">
            {/* The asset's name, not its storage key: rows read "logo", "favicon", "dark-logo" (#192). */}
            <span className="mc-branding__label">{t(`admin.site.asset.${key}`)}</span>
            <div className="mc-branding__previews">
              <span className="mc-branding__swatch mc-branding__swatch--light">
                <img src={`/branding/${key}?b=${bust}`} alt="" />
              </span>
              <span className="mc-branding__swatch mc-branding__swatch--dark">
                <img src={`/branding/${key}?b=${bust}`} alt="" />
              </span>
            </div>
            <div className="mc-branding__actions">
              {/* Visually hidden, not `hidden`: a `hidden` input leaves the tab order and a label is not
                  focusable, so logo, favicon and dark logo could not be uploaded without a mouse (core#163).
                  The input takes the focus; the label shows the ring for it. */}
              <label className="mc-btn mc-upload">
                {t('admin.site.upload')}
                <input
                  className="mc-sr-only"
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/x-icon,.ico"
                  aria-describedby="mc-branding-hint"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = '';
                    if (file) {
                      void uploadAsset(key, file);
                    }
                  }}
                />
              </label>
              <button type="button" className="mc-btn" onClick={() => clearAsset(key)}>
                {t('admin.site.clear')}
              </button>
            </div>
            {uploadError?.key === key && (
              <p className="mc-error" role="alert">
                {uploadError.message}
              </p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
