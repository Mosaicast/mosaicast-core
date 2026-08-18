// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { AdminBlobs } from '../../plugins/types';

/** One MiB, the unit the form works in — bytes are the wire's business, not an admin's. */
const MIB = 1024 * 1024;

/** Bytes → a MiB number for an input; rounded, since the form's own unit is MiB. */
function toMib(bytes: number): number {
  return Math.round(bytes / MIB);
}

/** A size for reading, not for editing: MiB until it stops being legible, then GiB. */
export function formatBytes(bytes: number): string {
  if (bytes >= MIB * 1024) {
    return `${(bytes / (MIB * 1024)).toFixed(bytes % (MIB * 1024) === 0 ? 0 : 1)} GiB`;
  }
  if (bytes >= 1024) {
    return `${Math.max(1, Math.round(bytes / MIB))} MiB`;
  }
  return `${bytes} B`;
}

/**
 * The per-plugin storage panel (ARCHITECTURE §11.1).
 *
 * A plugin's manifest says what its author expected it to need on an install they have never seen; this is
 * where someone looking at *this* install's usage decides otherwise. So the panel leads with what is
 * actually stored — an admin raising a ceiling wants to know why they are raising it — and an explicit grant
 * here **replaces** the manifest's ask rather than being minimised with it. Minimising would make this
 * control appear to work while doing nothing whenever a plugin asked for less than it was granted.
 *
 * Where the operator has configured a hard ceiling, the form says so rather than silently clamping what was
 * typed: a number that means something other than what it says is worse than a refusal.
 */
export function PluginStorage({
  blobs,
  onSave,
  onClear,
  saved,
}: {
  blobs: AdminBlobs;
  onSave: (quotaBytes: number, maxFileBytes: number) => void;
  onClear: () => void;
  saved: boolean;
}) {
  const { t } = useTranslation();
  const [quota, setQuota] = useState(() => String(toMib(blobs.quotaBytes)));
  const [maxFile, setMaxFile] = useState(() => String(toMib(blobs.maxFileBytes)));

  const quotaMib = Number(quota);
  const maxFileMib = Number(maxFile);
  const valid =
    Number.isFinite(quotaMib) && quotaMib > 0 && Number.isFinite(maxFileMib) && maxFileMib > 0;
  const overridden = blobs.quotaOverridden || blobs.maxFileOverridden;
  // Lowering below what is already stored is allowed — it is how an admin says "shrink". Nothing is
  // deleted; the plugin's own people have to remove enough before the next upload succeeds.
  const belowUsage = valid && quotaMib * MIB < blobs.usedBytes;

  return (
    <div className="mc-pluginrow__storage">
      <h3 className="mc-pluginrow__storageTitle">{t('admin.plugins.storage.title')}</h3>
      <p className="mc-muted">
        {t('admin.plugins.storage.usage', {
          used: formatBytes(blobs.usedBytes),
          quota: formatBytes(blobs.quotaBytes),
          count: blobs.fileCount,
        })}
      </p>

      <div className="mc-pluginrow__storageFields">
        <label className="mc-field">
          <span>{t('admin.plugins.storage.quota')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={1}
            value={quota}
            onChange={(e) => setQuota(e.target.value)}
          />
        </label>
        <label className="mc-field">
          <span>{t('admin.plugins.storage.maxFile')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={1}
            value={maxFile}
            onChange={(e) => setMaxFile(e.target.value)}
          />
        </label>
      </div>

      <p className="mc-muted mc-pluginrow__storageNote">
        {overridden
          ? t('admin.plugins.storage.overridden')
          : blobs.declaredQuotaBytes != null
            ? t('admin.plugins.storage.fromManifest', {
                quota: formatBytes(blobs.declaredQuotaBytes),
              })
            : t('admin.plugins.storage.fromDefault')}
      </p>
      {blobs.hardQuotaBytes != null && (
        <p className="mc-muted">
          {t('admin.plugins.storage.hardCap', { max: formatBytes(blobs.hardQuotaBytes) })}
        </p>
      )}
      {belowUsage && (
        <p className="mc-error" role="alert">
          {t('admin.plugins.storage.belowUsage')}
        </p>
      )}

      <div className="mc-form__actions">
        <button
          type="button"
          className="mc-btn mc-btn--accent"
          disabled={!valid}
          onClick={() => onSave(quotaMib * MIB, maxFileMib * MIB)}
        >
          {t('common.save')}
        </button>
        {overridden && (
          <button type="button" className="mc-btn" onClick={onClear}>
            {t('admin.plugins.storage.clear')}
          </button>
        )}
        {saved && <span className="mc-muted">{t('admin.plugins.saved')}</span>}
      </div>
    </div>
  );
}
