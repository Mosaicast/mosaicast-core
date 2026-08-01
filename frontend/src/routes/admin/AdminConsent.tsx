// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../../api/client';
import type { AdminConsentView } from '../../api/types';

/**
 * Admin → Consent: the audit half of §12.5.
 *
 * The visitor-facing payload deliberately names no plugins, which leaves an operator with a real question it
 * cannot answer — *why is this origin in my CSP, and who put it there?* This page answers exactly that: every
 * declared service attributed to the plugin that declared it, the resulting allow-list, and the fingerprint
 * the shell compares stored decisions against.
 *
 * There is no list of who consented to what, and there will not be one. A per-visitor consent table is a new
 * store of personal data — needing its own legal basis, retention and deletion — created to prove something
 * about visitors who are anonymous here. The visitor keeps their receipt on their own device (see
 * `ConsentContext`); the operator's half of the proof is what the site declared, which is this page.
 */
export function AdminConsent() {
  const { t } = useTranslation();
  const [view, setView] = useState<AdminConsentView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api
      .get<AdminConsentView>('/api/admin/consent')
      .then((fetched) => active && setView(fetched))
      .catch(() => active && setError(t('admin.consent.failed')));
    return () => {
      active = false;
    };
  }, [t]);

  if (error) {
    return <p className="mc-error">{error}</p>;
  }
  if (!view) {
    return null;
  }

  return (
    <section className="mc-form">
      <h2>{t('admin.consent.title')}</h2>
      <p className="mc-muted">{t('admin.consent.intro')}</p>

      {view.services.length === 0 ? (
        <p className="mc-muted">{t('admin.consent.none')}</p>
      ) : (
        <ul className="mc-list">
          {view.services.map((service) => (
            <li className="mc-list__row mc-consentaudit" key={`${service.pluginId}:${service.serviceId}`}>
              <div>
                <strong>{service.name}</strong>
                <span className="mc-muted"> — {service.provider}</span>
                <div className="mc-muted mc-consentaudit__meta">
                  {t('admin.consent.declaredBy', { plugin: service.pluginId })} · {service.category}
                  {!service.prompted && ` · ${t('admin.consent.notPrompted')}`}
                  {service.thirdCountryTransfer && ` · ${t('consent.thirdCountry')}`}
                </div>
                {service.hosts.length > 0 && (
                  <div className="mc-consentaudit__hosts">
                    {service.hosts.map((host) => (
                      <code key={host}>{host}</code>
                    ))}
                  </div>
                )}
                {service.storage.length > 0 && (
                  <ul className="mc-muted mc-consentaudit__storage">
                    {service.storage.map((item) => (
                      <li key={item.name}>
                        <code>{item.name}</code> · {item.type} · {item.purpose} · {item.duration}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      <h2>{t('admin.consent.csp')}</h2>
      <p className="mc-muted">{t('admin.consent.cspIntro')}</p>
      {view.csp.length === 0 ? (
        <p className="mc-muted">{t('admin.consent.cspNone')}</p>
      ) : (
        <ul className="mc-list">
          {view.csp.map((origin) => (
            <li className="mc-list__row" key={origin}>
              <code>{origin}</code>
            </li>
          ))}
        </ul>
      )}

      <p className="mc-muted">
        {t('admin.consent.fingerprint')} <code>{view.fingerprint}</code>
      </p>
    </section>
  );
}
