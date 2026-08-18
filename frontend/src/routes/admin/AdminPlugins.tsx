// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import type { AdminConfigField, AdminPlugin } from '../../plugins/types';
import { PluginStorage } from './PluginStorage';

/**
 * The admin plugin surface (ARCHITECTURE §7.2/§7.8): every discovered plugin with its load state, an
 * activation toggle, the config form generated from the manifest's declared fields, and the explicit
 * "purge plugin data" action. Backed by `GET /api/admin/plugins` and its three write endpoints (ADMIN).
 *
 * Plugins never ship a config UI — this form is rendered purely from the declaration (`type`, `default`,
 * `editableBy`), which is why the manifest rejects field types the host cannot render.
 */
export function AdminPlugins() {
  const { t } = useTranslation();
  const [plugins, setPlugins] = useState<AdminPlugin[] | null>(null);
  /** Unsaved form input, keyed `${pluginId} ${field}`; absent means "unchanged from the server value". */
  const [drafts, setDrafts] = useState<Record<string, string | boolean>>({});
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setPlugins(await api.get<AdminPlugin[]>('/api/admin/plugins'));
    } catch {
      setPlugins([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (action: () => Promise<unknown>, savedId: string | null) => {
    setError(null);
    setSaved(null);
    try {
      await action();
      setSaved(savedId);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? (e.detail ?? e.message) : String(e));
    }
  };

  const toggle = (plugin: AdminPlugin) =>
    void run(() => api.put(`/api/admin/plugins/${plugin.id}/enabled?value=${!plugin.enabled}`), null);

  const purge = (plugin: AdminPlugin) => {
    if (!window.confirm(t('admin.plugins.purgeConfirm', { name: plugin.name ?? plugin.id }))) {
      return;
    }
    void run(async () => {
      const result = await api.post<{ purged: number }>(`/api/admin/plugins/${plugin.id}/purge`);
      window.alert(t('admin.plugins.purged', { count: result.purged }));
    }, null);
  };

  const saveConfig = (plugin: AdminPlugin) => {
    const body: Record<string, string | number | boolean | null> = {};
    for (const [field, declared] of Object.entries(plugin.config)) {
      const draft = drafts[draftKey(plugin.id, field)];
      if (draft === undefined) {
        continue;
      }
      body[field] = toJsonValue(draft, declared);
    }
    void run(async () => {
      await api.put(`/api/admin/plugins/${plugin.id}/config`, body);
      setDrafts((current) => {
        const next = { ...current };
        Object.keys(plugin.config).forEach((field) => delete next[draftKey(plugin.id, field)]);
        return next;
      });
    }, plugin.id);
  };

  /** Clearing an override is a save of `null` — the manifest default applies again. */
  const resetField = (plugin: AdminPlugin, field: string) =>
    void run(async () => {
      await api.put(`/api/admin/plugins/${plugin.id}/config`, { [field]: null });
      setDrafts((current) => {
        const next = { ...current };
        delete next[draftKey(plugin.id, field)];
        return next;
      });
    }, plugin.id);

  /**
   * Storage limits are their own endpoint, not a config field: they are a host decision about the
   * installation's disk rather than something the plugin declared, and they are ADMIN-only where `/config`
   * is open to PODCASTER.
   */
  const saveStorage = (plugin: AdminPlugin, quotaBytes: number | null, maxFileBytes: number | null) =>
    void run(
      () => api.put(`/api/admin/plugins/${plugin.id}/blob-limits`, { quotaBytes, maxFileBytes }),
      plugin.id,
    );

  if (plugins == null) {
    return <p className="mc-muted">{t('common.loading')}</p>;
  }

  const rejected = plugins.filter((p) => p.status === 'REJECTED');

  return (
    <div className="mc-form">
      <h2>{t('admin.plugins.title')}</h2>
      {rejected.length > 0 && (
        <div className="mc-banner mc-banner--error" role="alert">
          <span>{t('admin.plugins.rejectedWarning', { count: rejected.length })}</span>
        </div>
      )}
      {error && <p className="mc-error">{error}</p>}
      {plugins.length === 0 ? (
        <p className="mc-muted">{t('admin.plugins.empty')}</p>
      ) : (
        <ul className="mc-list">
          {plugins.map((plugin) => (
            <li key={plugin.id} className="mc-pluginrow">
              <div className="mc-pluginrow__head">
                <div>
                  <span className="mc-identity__provider">{plugin.name ?? plugin.id}</span>{' '}
                  <span className="mc-muted">
                    {plugin.id}
                    {plugin.version ? ` · ${plugin.version}` : ''}
                  </span>
                  {plugin.reason && <p className="mc-error">{plugin.reason}</p>}
                  {plugin.status === 'LOADED' && !plugin.enabled && (
                    <p className="mc-muted">{t('admin.plugins.stopsAtRestart')}</p>
                  )}
                </div>
                <div className="mc-pluginrow__actions">
                  {plugin.status !== 'REJECTED' && (
                    <label className="mc-toggle">
                      <input type="checkbox" checked={plugin.enabled} onChange={() => toggle(plugin)} />
                      {t('admin.plugins.enabled')}
                    </label>
                  )}
                  <span className={statusClass(plugin)}>{t(statusKey(plugin))}</span>
                  <button type="button" className="mc-btn" onClick={() => purge(plugin)}>
                    {t('admin.plugins.purge')}
                  </button>
                </div>
              </div>

              {plugin.blobs && (
                <PluginStorage
                  blobs={plugin.blobs}
                  saved={saved === plugin.id}
                  onSave={(quota, maxFile) => saveStorage(plugin, quota, maxFile)}
                  onClear={() => saveStorage(plugin, null, null)}
                />
              )}

              {Object.keys(plugin.config ?? {}).length > 0 && (
                <div className="mc-pluginrow__config">
                  {Object.entries(plugin.config).map(([field, declared]) => (
                    <ConfigInput
                      key={field}
                      field={field}
                      declared={declared}
                      draft={drafts[draftKey(plugin.id, field)]}
                      onChange={(value) =>
                        setDrafts((current) => ({ ...current, [draftKey(plugin.id, field)]: value }))
                      }
                      onReset={() => resetField(plugin, field)}
                      resetLabel={t('admin.plugins.resetField')}
                      editableByLabel={t('admin.plugins.editableBy', { role: declared.editableBy })}
                    />
                  ))}
                  <div className="mc-form__actions">
                    <button
                      type="button"
                      className="mc-btn mc-btn--accent"
                      onClick={() => saveConfig(plugin)}
                    >
                      {t('common.save')}
                    </button>
                    {saved === plugin.id && <span className="mc-muted">{t('admin.plugins.saved')}</span>}
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** One generated form row; the input kind follows the field's declared type. */
function ConfigInput({
  field,
  declared,
  draft,
  onChange,
  onReset,
  resetLabel,
  editableByLabel,
}: {
  field: string;
  declared: AdminConfigField;
  draft: string | boolean | undefined;
  onChange: (value: string | boolean) => void;
  onReset: () => void;
  resetLabel: string;
  editableByLabel: string;
}) {
  const current = draft ?? declared.value ?? '';

  return (
    <label className="mc-field">
      <span>
        {field} <span className="mc-muted">{editableByLabel}</span>
      </span>
      {declared.type === 'boolean' ? (
        <input type="checkbox" checked={Boolean(current)} onChange={(e) => onChange(e.target.checked)} />
      ) : (
        <input
          className={declared.type === 'number' ? 'mc-input mc-input--num' : 'mc-input'}
          type={declared.type === 'number' ? 'number' : 'text'}
          value={String(current)}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      {declared.overridden && (
        <button type="button" className="mc-btn" onClick={onReset}>
          {resetLabel}
        </button>
      )}
    </label>
  );
}

function draftKey(pluginId: string, field: string) {
  return `${pluginId} ${field}`;
}

/** Form input is text; the declared type decides what JSON the endpoint receives. */
function toJsonValue(draft: string | boolean, declared: AdminConfigField): string | number | boolean | null {
  if (declared.type === 'boolean') {
    return Boolean(draft);
  }
  if (declared.type === 'number') {
    const parsed = Number(draft);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return String(draft);
}

function statusKey(plugin: AdminPlugin) {
  if (plugin.status === 'REJECTED') {
    return 'admin.plugins.rejected';
  }
  return plugin.enabled ? 'admin.plugins.loaded' : 'admin.plugins.disabled';
}

function statusClass(plugin: AdminPlugin) {
  return plugin.status === 'LOADED' && plugin.enabled ? 'mc-chip' : 'mc-chip mc-chip--lock';
}
