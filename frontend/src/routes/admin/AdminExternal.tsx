// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AdminKindSection, AdminProbeResult } from '../../api/types';
import {
  SettingsFieldInput,
  isSecretType,
  toJsonValue,
  type DraftValue,
} from '../../components/SettingsFieldInput';
import { problemMessage } from '../../api/problemMessage';
import { SavedNote } from '../../a11y/SavedNote';

/**
 * External services admin (ARCHITECTURE §12.7, ADMIN only): one section per service *kind*, where the admin
 * picks which third-party service this instance uses — or none — and fills in what it needs.
 *
 * The whole page is driven by the API's list, so a new kind costs the frontend two i18n keys and nothing
 * else. Nothing is selected by default: a service nobody configured must make no outbound call.
 */
export function AdminExternal() {
  const { t } = useTranslation();

  const [sections, setSections] = useState<AdminKindSection[] | null>(null);
  const [drafts, setDrafts] = useState<Record<string, DraftValue>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [probe, setProbe] = useState<Record<string, AdminProbeResult>>({});

  useEffect(() => {
    api
      .get<AdminKindSection[]>('/api/admin/external')
      .then(setSections)
      .catch(() => setError(t('admin.external.loadFailed')));
  }, [t]);

  const apply = (updated: AdminKindSection) =>
    setSections((current) =>
      (current ?? []).map((section) => (section.kind === updated.kind ? updated : section)),
    );

  // In the admin's language when the server named the reason (e.g. an address it refused), else its English
  // detail (#192).
  const messageOf = (problem: unknown, fallback: string) => problemMessage(problem, t, fallback);

  const selectProvider = async (kind: string, providerId: string) => {
    setError(null);
    setSaved(null);
    try {
      apply(
        await api.put<AdminKindSection>(`/api/admin/external/${kind}/provider`, {
          providerId: providerId === '' ? null : providerId,
        }),
      );
    } catch (problem) {
      setError(messageOf(problem, t('admin.external.saveFailed')));
    }
  };

  const saveSettings = async (kind: string, providerId: string, fields: { key: string; type: string }[]) => {
    setError(null);
    setSaved(null);
    const body: Record<string, unknown> = {};
    for (const field of fields) {
      const draft = drafts[draftKey(kind, providerId, field.key)];
      if (draft === undefined) {
        continue;
      }
      // A credential the admin left blank is not "clear it" — it is "I did not retype the one already
      // stored", which is what an empty write-only box looks like every time the page loads.
      if (isSecretType(field.type) && draft === '') {
        continue;
      }
      body[field.key] = toJsonValue(draft, field.type);
    }
    try {
      const updated = await api.put<AdminKindSection>(
        `/api/admin/external/${kind}/providers/${providerId}/settings`,
        body,
      );
      apply(updated);
      // Drop the drafts we just persisted so a stored credential's box goes back to empty.
      setDrafts((current) => {
        const next = { ...current };
        for (const field of fields) {
          delete next[draftKey(kind, providerId, field.key)];
        }
        return next;
      });
      setSaved(kind);
    } catch (problem) {
      setError(messageOf(problem, t('admin.external.saveFailed')));
    }
  };

  const runProbe = async (kind: string) => {
    setError(null);
    try {
      const result = await api.post<AdminProbeResult>(`/api/admin/external/${kind}/test`, {});
      setProbe((current) => ({ ...current, [kind]: result }));
    } catch (problem) {
      setProbe((current) => ({
        ...current,
        [kind]: { ok: false, detail: messageOf(problem, t('admin.external.testFailed')), millis: 0 },
      }));
    }
  };

  if (!sections) {
    return <p className="mc-muted">{error ?? t('common.loading')}</p>;
  }

  return (
    <div className="mc-form">
      <h2>{t('admin.external.title')}</h2>
      <p className="mc-muted">{t('admin.external.intro')}</p>
      {error && <p className="mc-error">{error}</p>}

      {sections.map((section) => {
        const selected = section.providers.find((p) => p.id === section.selectedProviderId);
        const result = probe[section.kind];
        return (
          <section key={section.kind} className="mc-extkind">
            <h3>{t(`admin.external.kind.${section.kind}`)}</h3>

            <label className="mc-field">
              <span>{t('admin.external.provider')}</span>
              {section.providers.length === 0 ? (
                <span className="mc-muted">{t('admin.external.noProviders')}</span>
              ) : (
                <select
                  value={section.selectedProviderId ?? ''}
                  onChange={(e) => selectProvider(section.kind, e.target.value)}
                >
                  <option value="">{t('admin.external.none')}</option>
                  {section.providers.map((provider) => (
                    <option key={provider.id} value={provider.id}>
                      {provider.name}
                    </option>
                  ))}
                </select>
              )}
            </label>

            {selected && (
              <>
                {/* A built-in provider's texts are the host's, so they are translated here; the server's English
                    stays the fallback for a provider the catalog has no entry for (#192). */}
                <p className="mc-muted">
                  {t(`admin.external.providers.${selected.id}.description`, { defaultValue: selected.description })}
                </p>
                <p className="mc-extkind__notes">
                  {selected.selfHosted && (
                    <span className="mc-chip mc-chip--quiet">{t('admin.external.selfHosted')}</span>
                  )}
                  {selected.paid && <span className="mc-chip">{t('admin.external.paid')}</span>}
                  {/* Shown before the admin commits: choosing a provider decides whose servers this
                      site's text is sent to, which is not something to discover afterwards. */}
                  {selected.thirdCountryTransfer && (
                    <span className="mc-chip mc-chip--warn">{t('admin.external.thirdCountry')}</span>
                  )}
                  {selected.privacyUrl && (
                    <a href={selected.privacyUrl} target="_blank" rel="noreferrer">
                      {t('admin.external.privacyPolicy')}
                    </a>
                  )}
                </p>

                {!section.encryptsSecrets && selected.fields.some((f) => f.type === 'SECRET') && (
                  <p className="mc-error">{t('admin.external.noEncryptionKey')}</p>
                )}

                {selected.fields.map((field) => (
                  <SettingsFieldInput
                    key={field.key}
                    field={{
                      ...field,
                      label: t(`admin.external.providers.${selected.id}.fields.${field.key}.label`, {
                        defaultValue: field.label,
                      }),
                      description: t(`admin.external.providers.${selected.id}.fields.${field.key}.hint`, {
                        defaultValue: field.description,
                      }),
                    }}
                    value={
                      drafts[draftKey(section.kind, selected.id, field.key)] ??
                      (isSecretType(field.type) ? '' : ((field.value as DraftValue) ?? ''))
                    }
                    onChange={(value) =>
                      setDrafts((current) => ({
                        ...current,
                        [draftKey(section.kind, selected.id, field.key)]: value,
                      }))
                    }
                  />
                ))}

                <div className="mc-form__actions">
                  <button
                    type="button"
                    className="mc-btn mc-btn--accent"
                    onClick={() => saveSettings(section.kind, selected.id, selected.fields)}
                  >
                    {t('common.save')}
                  </button>
                  <button
                    type="button"
                    className="mc-btn"
                    // Disabled until the host says a call would be attempted at all — letting a click
                    // 409 would only teach the admin to distrust the button.
                    disabled={!section.ready}
                    onClick={() => runProbe(section.kind)}
                  >
                    {t('admin.external.test')}
                  </button>
                  <SavedNote show={saved === section.kind}>{t('admin.external.saved')}</SavedNote>
                  {result && (
                    <span className={`mc-chip ${result.ok ? 'mc-chip--ok' : 'mc-chip--error'}`}>
                      {result.ok
                        ? t('admin.external.testOk', { millis: result.millis })
                        : result.detail}
                    </span>
                  )}
                </div>

                {!section.ready && section.missingSettings.length > 0 && (
                  <p className="mc-muted">
                    {/* By label, as the fields above are named — the raw key read "Fehlt noch: baseUrl" (#192). */}
                    {t('admin.external.stillNeeds', {
                      fields: section.missingSettings
                        .map((key) =>
                          t(`admin.external.providers.${selected.id}.fields.${key}.label`, {
                            defaultValue: selected.fields.find((f) => f.key === key)?.label ?? key,
                          }),
                        )
                        .join(', '),
                    })}
                  </p>
                )}
              </>
            )}
          </section>
        );
      })}
    </div>
  );
}

function draftKey(kind: string, providerId: string, field: string) {
  return `${kind} ${providerId} ${field}`;
}
