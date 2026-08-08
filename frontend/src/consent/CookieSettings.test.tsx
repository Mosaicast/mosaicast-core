// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ConsentPayload } from '../api/types';
import '../i18n';
import { ConsentProvider } from './ConsentContext';
import { CookieSettings } from './CookieSettings';

const PAYLOAD: ConsentPayload = {
  fingerprint: 'abc123',
  categories: [
    {
      id: 'analytics',
      known: true,
      affectsPolicy: true,
      services: [
        {
          name: 'Plausible Analytics',
          provider: 'Plausible Insights OÜ',
          privacyUrl: 'https://plausible.example/privacy',
          thirdCountryTransfer: true,
          storage: [{ name: 'pa', type: 'cookie', purpose: 'counts a visit', duration: '24 hours' }],
        },
      ],
    },
  ],
  essential: {
    storage: [
      {
        name: 'mc.progress.*',
        type: 'localStorage',
        purposeKey: 'consent.purpose.progress',
        durationKey: 'consent.duration.persistent',
        optional: true,
      },
    ],
  },
  necessaryServices: [],
  privacySlug: 'privacy',
};

function stubConsent(payload: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) })),
  );
}

function renderSettings() {
  return render(
    <MemoryRouter>
      <ConsentProvider>
        <CookieSettings />
      </ConsentProvider>
    </MemoryRouter>,
  );
}

describe('Cookie settings (§12.5)', () => {
  beforeEach(() => {
    localStorage.clear();
    // A decision that moves the CSP reloads, which jsdom cannot do. The two tests that assert *whether* it
    // reloads stub their own; this keeps the rest from logging a navigation error they are not about.
    vi.stubGlobal('location', { ...window.location, reload: vi.fn() });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('discloses what each service stores, and who runs it', async () => {
    stubConsent(PAYLOAD);
    renderSettings();

    expect(await screen.findByText(/run by Plausible Insights OÜ/)).toBeInTheDocument();
    expect(screen.getByText('Privacy policy of Plausible Analytics')).toBeInTheDocument();
    // Name, purpose and lifetime of each stored item — the disclosure §25 TDDDG asks for.
    expect(screen.getByText('pa')).toBeInTheDocument();
    expect(screen.getByText('counts a visit')).toBeInTheDocument();
    expect(screen.getByText('24 hours')).toBeInTheDocument();
    expect(screen.getByText(/outside the EU\/EEA/)).toBeInTheDocument();
  });

  it("shows the core's own storage with a translated purpose, even with nothing to consent to", async () => {
    stubConsent({ ...PAYLOAD, categories: [] });
    renderSettings();

    // The purpose arrives as an i18n key, because only the shell knows the active locale.
    expect(await screen.findByText('Remembers where you stopped listening')).toBeInTheDocument();
    expect(screen.getByText('mc.progress.*')).toBeInTheDocument();
  });

  it('forgets stored positions when the visitor switches remembering off', async () => {
    localStorage.setItem('mc.progress.abc', '120');
    stubConsent(PAYLOAD);
    renderSettings();

    const toggle = await screen.findByLabelText('Remember where I stopped listening');
    fireEvent.click(toggle);

    // Switching it off is also a request to forget — leaving the positions behind would keep storing
    // exactly what the visitor just asked not to have stored.
    await waitFor(() => expect(localStorage.getItem('mc.progress.abc')).toBeNull());
    expect(localStorage.getItem('mc.prefs.progress')).toBe('off');

    // And on the server too, for a signed-in listener. Clearing the local keys only meant someone signed in
    // kept a server-side history of what they had listened to and how far, restored on the next play, while
    // this page told them the positions were deleted.
    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        '/api/me/progress',
        expect.objectContaining({ method: 'DELETE' }),
      ),
    );
  });

  it('applies a decision to the page it was made on, not only to the next one', async () => {
    // Enforcement is the response CSP, and a document's CSP cannot be changed after it is delivered. Setting
    // the cookie and firing the listeners left the plugin's script blocked against the policy already in
    // force — and left a refusal unenforced for the rest of the session, contradicting what ConsentCookie
    // documents it guarantees. Routing is client-side, so nothing else would ever re-request the document.
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    stubConsent(PAYLOAD);
    renderSettings();

    fireEvent.click(await screen.findByRole('button', { name: 'Allow all' }));

    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('does not reload when the answer changes nothing the browser would enforce', async () => {
    // A visitor who reopens the settings and saves the same answer, or toggles a category no service declares
    // an origin for, must not lose their place in an episode for a policy that did not move.
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    stubConsent({
      ...PAYLOAD,
      categories: [{ ...PAYLOAD.categories[0]!, affectsPolicy: false }],
    });
    renderSettings();

    fireEvent.click(await screen.findByRole('button', { name: 'Allow all' }));

    await waitFor(() => expect(localStorage.getItem('mc.consent')).toContain('analytics'));
    expect(reload).not.toHaveBeenCalled();
  });

  it('leaves the playback position off when the browser objected, until the visitor says otherwise', async () => {
    vi.stubGlobal('navigator', { ...navigator, globalPrivacyControl: true });
    stubConsent(PAYLOAD);
    renderSettings();

    // On by default is defensible for someone who said nothing. It is not defensible for someone whose
    // browser is asking sites not to track them — and a position that persists indefinitely is exactly the
    // case the strictly-necessary exemption covers least well.
    const toggle = await screen.findByLabelText('Remember where I stopped listening');
    expect(toggle).not.toBeChecked();
    expect(screen.getByText(/Off because your browser asked sites not to track you/)).toBeInTheDocument();

    // Still a switch, not a lock: an explicit choice outranks the signal from then on.
    fireEvent.click(toggle);
    await waitFor(() => expect(localStorage.getItem('mc.prefs.progress')).toBe('on'));
    expect(toggle).toBeChecked();
  });

  it('records a receipt the visitor can read back', async () => {
    stubConsent(PAYLOAD);
    renderSettings();

    fireEvent.click(await screen.findByText('Allow all'));

    expect(await screen.findByText(/You decided on/)).toBeInTheDocument();
    expect(screen.getByText('Allowed')).toBeInTheDocument();
    const stored = JSON.parse(localStorage.getItem('mc.consent') ?? '{}');
    expect(stored.fingerprint).toBe('abc123');
    expect(stored.categories).toEqual({ analytics: true });
    expect(Date.parse(stored.decidedAt)).toBeGreaterThan(0);
  });

  it('takes the data with it when consent is withdrawn', async () => {
    stubConsent(PAYLOAD);
    renderSettings();
    fireEvent.click(await screen.findByText('Allow all'));
    await screen.findByText('Allowed');
    // Written while the category was granted, exactly as the service declared it.
    localStorage.setItem('pa', 'visit-1');

    fireEvent.click(screen.getByText('Allow none'));

    // Withdrawal is not only "stop collecting" — Art. 17 and "as easy as granting" mean what is already on
    // the device goes as well. The CSP closes the future; this closes the past.
    await waitFor(() => expect(localStorage.getItem('pa')).toBeNull());
  });

  it('sweeps a key nothing declared, without needing whoever wrote it to cooperate', async () => {
    localStorage.setItem('rogue.id', 'uuid');
    stubConsent(PAYLOAD);
    renderSettings();

    await waitFor(() => expect(localStorage.getItem('rogue.id')).toBeNull());
  });

  it('discloses services declared necessary, which are never offered as a choice', async () => {
    stubConsent({
      ...PAYLOAD,
      necessaryServices: [
        {
          name: 'Host Badge CDN',
          provider: 'Badge Ltd',
          privacyUrl: null,
          thirdCountryTransfer: false,
          storage: [{ name: 'badge.cache', type: 'localStorage', purpose: 'caches the badge', duration: 'a week' }],
        },
      ],
    });
    renderSettings();

    // No toggle for it — that is the point of `necessary` — but §25 TDDDG asks for the disclosure whether or
    // not there is a decision attached to it.
    expect(await screen.findByText('Host Badge CDN')).toBeInTheDocument();
    expect(screen.getByText('badge.cache')).toBeInTheDocument();
  });

  it('withdraws in one click, exactly like granting', async () => {
    stubConsent(PAYLOAD);
    renderSettings();
    fireEvent.click(await screen.findByText('Allow all'));
    await screen.findByText('Allowed');

    fireEvent.click(screen.getByText('Allow none'));

    await waitFor(() => expect(screen.getByText('Not allowed')).toBeInTheDocument());
    expect(JSON.parse(localStorage.getItem('mc.consent') ?? '{}').categories).toEqual({ analytics: false });
  });
});
