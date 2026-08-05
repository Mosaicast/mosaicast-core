// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { useConsent } from './ConsentContext';

/**
 * The playback-position switch (ARCHITECTURE §12.5), in one component because it appears in two places:
 * the privacy settings, where it belongs next to the disclosure of `mc.progress.*`, and the account page,
 * where a signed-in listener will actually look for it.
 *
 * Not a consent category on purpose — it is first-party, stays on the device, is never profiled, and is
 * only written after a deliberate press of play, so it is part of the service the visitor asked for.
 * Gating it behind a banner would trade a real feature for a fake choice; disclosing it and giving it an
 * off switch is the honest arrangement.
 *
 * @param note an extra line of context — the account page uses it to say that the switch itself is
 *             per-device, since everything else on that page is per-account
 */
export function ProgressPreference({ note }: { note?: string }) {
  const { t } = useTranslation();
  const { progressEnabled, setProgressEnabled, gpc } = useConsent();

  return (
    <div className="mc-consent__pref">
      <label className="mc-toggle">
        <input
          type="checkbox"
          checked={progressEnabled}
          onChange={(event) => setProgressEnabled(event.target.checked)}
        />
        <span>{t('consent.progress.title')}</span>
      </label>
      <p className="mc-muted mc-consent__hint">{t('consent.progress.hint')}</p>
      {/* Otherwise an off switch on the account page, which shows no consent notice, looks like a bug. */}
      {gpc && !progressEnabled && <p className="mc-muted mc-consent__hint">{t('consent.progress.gpc')}</p>}
      {note && <p className="mc-muted mc-consent__hint">{note}</p>}
    </div>
  );
}
