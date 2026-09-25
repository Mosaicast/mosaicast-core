// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError, api } from '../../api/client';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { SettingsFieldInput, toJsonValue, type DraftValue } from '../../components/SettingsFieldInput';
import { useUser } from '../../auth/UserContext';
import { localizedText, optionLabel, type AdminPlugin } from '../../plugins/types';
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
  const { t, i18n } = useTranslation();
  // A podcaster reaches this page for the fields their manifests delegate to them (§7.2). Everything else
  // here is ADMIN on the server, so the controls for it are not rendered rather than rendered to fail.
  const { user } = useUser();
  const isAdmin = user?.role === 'admin';
  const [plugins, setPlugins] = useState<AdminPlugin[] | null>(null);
  /** Unsaved form input, keyed `${pluginId} ${field}`; absent means "unchanged from the server value". */
  const [drafts, setDrafts] = useState<Record<string, string | boolean>>({});
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [purged, setPurged] = useState<string | null>(null);

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

  // The most destructive action in the product — irreversible, and it affects every user of that plugin —
  // used to be one OK-click away in an unstyled browser dialog, while account deletion (narrower blast
  // radius, equally irreversible) correctly required typing a word (core#193).
  const [purging, setPurging] = useState<AdminPlugin | null>(null);

  const purge = (plugin: AdminPlugin) => {
    setPurging(null);
    void run(async () => {
      const result = await api.post<{ purged: number }>(
        `/api/admin/plugins/${plugin.id}/purge?confirm=${encodeURIComponent(plugin.id)}`,
      );
      // A notice in the page rather than window.alert: the count is information, and it belongs where the
      // rest of this page's feedback is.
      setPurged(t('admin.plugins.purged', { count: result.purged }));
    }, null);
  };

  const saveConfig = (plugin: AdminPlugin) => {
    const body: Record<string, string | number | boolean | null> = {};
    for (const [field, declared] of Object.entries(plugin.config)) {
      const draft = drafts[draftKey(plugin.id, field)];
      if (draft === undefined) {
        continue;
      }
      // A closed set carries its values in the manifest's own types, and the form only ever held the
      // stringified form of one of them. Send the option back rather than re-parsing the string:
      // `Boolean('false')` is `true`, so a boolean set would save the opposite of what was picked.
      const chosen = declared.options?.find((option) => String(option.value) === String(draft));
      body[field] = chosen ? chosen.value : toJsonValue(draft, declared.type);
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
      {purged && (
        <p className="mc-muted" role="status">
          {purged}
        </p>
      )}
      {purging && (
        <ConfirmDialog
          title={t('admin.plugins.purge')}
          body={t('admin.plugins.purgeConfirm', { name: purging.name ?? purging.id })}
          confirmLabel={t('admin.plugins.purge')}
          // The plugin's own id: a typed word that is different for each plugin, so the muscle memory of
          // purging one does not carry over to purging another.
          confirmWord={purging.id}
          onConfirm={() => purge(purging)}
          onCancel={() => setPurging(null)}
        />
      )}
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
                  {isAdmin && plugin.status !== 'REJECTED' && (
                    <label className="mc-toggle">
                      <input type="checkbox" checked={plugin.enabled} onChange={() => toggle(plugin)} />
                      {t('admin.plugins.enabled')}
                    </label>
                  )}
                  {/* Status is readable by anyone who can see the row: a podcaster editing a field of a
                      plugin that is switched off needs to know that before wondering why nothing happens. */}
                  <span className={statusClass(plugin)}>{t(statusKey(plugin))}</span>
                  {isAdmin && (
                    <button type="button" className="mc-btn" onClick={() => setPurging(plugin)}>
                      {t('admin.plugins.purge')}
                    </button>
                  )}
                </div>
              </div>

              {/* What this plugin may spend, read straight off its manifest (§16). Here rather than only in
                  plugin.json because the decision it informs — whether to run this plugin at all — is made
                  on this page, and the floor is the effective one the host enforces, not the file's. */}
              {plugin.external && (
                <div className="mc-pluginrow__external">
                  <p className="mc-pluginrow__storageTitle">{t('admin.plugins.external.title')}</p>
                  <p className="mc-muted">
                    {t('admin.plugins.external.uses', {
                      kinds: plugin.external.kinds
                        .map((kind) => t(`admin.external.kind.${kind}`, kind))
                        .join(', '),
                    })}
                  </p>
                  {plugin.external.usedBy === 'anonymous' ? (
                    // The same treatment the plaintext-credentials note on the external-services page gets:
                    // an anonymous floor in front of a metered provider is an open spending endpoint (§16).
                    <p className="mc-error">{t('admin.plugins.external.usedByAnyone')}</p>
                  ) : (
                    <p className="mc-muted">
                      {t('admin.plugins.external.usedBy', {
                        role: t(`role.${plugin.external.usedBy}`, plugin.external.usedBy),
                      })}
                    </p>
                  )}
                </div>
              )}

              {isAdmin && plugin.blobs && (
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
                    <SettingsFieldInput
                      key={field}
                      field={{
                        key: field,
                        // A field that declares a closed set is rendered as a select. Only the rendering
                        // changes: saving still coerces through `declared.type`, so a numeric set is sent
                        // as a number rather than as the string the form held.
                        type: declared.options?.length ? 'SELECT' : declared.type,
                        options: declared.options?.map((option) => ({
                          value: String(option.value),
                          label: optionLabel(option, i18n.language),
                        })),
                        // The manifest's own prose, in the reader's language. Absent leaves the raw key,
                        // which is what every field showed before a plugin could say anything about one.
                        label: localizedText(declared.label, i18n.language),
                        description: localizedText(declared.description, i18n.language),
                        // Shown but not editable for a role the server would refuse. Saving is
                        // all-or-nothing, so a podcaster typing into an admin-only row would lose the edits
                        // they were allowed to make along with the one they were not.
                        disabled: !isAdmin && declared.editableBy !== 'podcaster',
                        overridden: declared.overridden,
                      }}
                      value={drafts[draftKey(plugin.id, field)] ?? (declared.value as DraftValue) ?? ''}
                      onChange={(value) =>
                        setDrafts((current) => ({ ...current, [draftKey(plugin.id, field)]: value }))
                      }
                      onReset={() => resetField(plugin, field)}
                      resetLabel={t('admin.plugins.resetField')}
                      hint={t('admin.plugins.editableBy', { role: declared.editableBy })}
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

function draftKey(pluginId: string, field: string) {
  return `${pluginId} ${field}`;
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
