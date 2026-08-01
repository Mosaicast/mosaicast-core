// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ConsentPayload } from '../api/types';
import '../i18n';
import { buildCtx } from '../plugins/buildCtx';
import { ConsentBanner } from './ConsentBanner';
import { ConsentProvider, useConsent } from './ConsentContext';

const ESSENTIAL = {
  storage: [
    {
      name: 'mc.locale',
      type: 'localStorage',
      purposeKey: 'consent.purpose.locale',
      durationKey: 'consent.duration.persistent',
      optional: false,
    },
  ],
};

const WITH_CATEGORY: ConsentPayload = {
  fingerprint: 'abc123',
  categories: [
    {
      id: 'analytics',
      known: true,
      services: [
        {
          name: 'Plausible Analytics',
          provider: 'Plausible Insights OÜ',
          privacyUrl: 'https://plausible.example/privacy',
          thirdCountryTransfer: false,
          storage: [
            { name: 'pa', type: 'cookie', purpose: 'counts a visit', duration: '24 hours' },
          ],
        },
      ],
    },
  ],
  essential: ESSENTIAL,
  privacySlug: 'privacy',
};

const NOTHING: ConsentPayload = {
  fingerprint: 'empty1',
  categories: [],
  essential: ESSENTIAL,
  privacySlug: null,
};

function stubConsent(payload: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) })),
  );
}

/** Renders the banner plus a probe showing what `ctx.consent.has` would return for a plugin. */
function Probe() {
  const consent = useConsent();
  const ctx = buildCtx({
    pluginId: 'stats',
    scope: { type: 'site', id: 'main' },
    episodes: [],
    episodeLabels: {},
    user: null,
    theme: undefined,
    locale: 'en',
    playerCurrentTime: () => 0,
    playerSeekTo: () => {},
    consentHas: consent.has,
  });
  return <span data-testid="granted">{String(ctx.consent.has('analytics'))}</span>;
}

function renderBanner() {
  return render(
    <MemoryRouter>
      <ConsentProvider>
        <ConsentBanner />
        <Probe />
      </ConsentProvider>
    </MemoryRouter>,
  );
}

const TITLE = 'Before you carry on';

describe('Consent (§12.5)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('stays banner-free when no service is declared', async () => {
    stubConsent(NOTHING);
    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toBeInTheDocument());
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  it('names the company rather than the plugin, and denies until the visitor agrees', async () => {
    stubConsent(WITH_CATEGORY);
    renderBanner();

    expect(await screen.findByText(TITLE)).toBeInTheDocument();
    // The wording rule, asserted: a visitor is told who is involved, not which plugin asked.
    expect(screen.getByText(/Plausible Insights OÜ/)).toBeInTheDocument();
    expect(screen.queryByText(/stats/)).not.toBeInTheDocument();
    // Default deny while undecided.
    expect(screen.getByTestId('granted')).toHaveTextContent('false');

    fireEvent.click(screen.getByText('Allow all'));
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('true'));
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  it('persists a refusal, so the banner does not ask again', async () => {
    stubConsent(WITH_CATEGORY);
    const first = renderBanner();
    expect(await screen.findByText(TITLE)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Allow none'));
    await waitFor(() => expect(screen.queryByText(TITLE)).not.toBeInTheDocument());
    first.unmount();

    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('false'));
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  it('asks again when the declared set changed', async () => {
    stubConsent(WITH_CATEGORY);
    const first = renderBanner();
    expect(await screen.findByText(TITLE)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Allow all'));
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('true'));
    first.unmount();

    // A newly installed service moves the fingerprint. Consent given for one company is not consent for
    // another, and the stored answer must stop counting rather than quietly covering it.
    stubConsent({ ...WITH_CATEGORY, fingerprint: 'different' });
    renderBanner();
    expect(await screen.findByText(TITLE)).toBeInTheDocument();
    expect(screen.getByTestId('granted')).toHaveTextContent('false');
  });

  it('stops honouring an answer older than twelve months', async () => {
    localStorage.setItem(
      'mc.consent',
      JSON.stringify({
        version: 1,
        decidedAt: new Date(Date.now() - 400 * 24 * 60 * 60 * 1000).toISOString(),
        fingerprint: 'abc123',
        categories: { analytics: true },
      }),
    );
    stubConsent(WITH_CATEGORY);
    renderBanner();

    expect(await screen.findByText(TITLE)).toBeInTheDocument();
    expect(screen.getByTestId('granted')).toHaveTextContent('false');
  });

  it('treats a Global Privacy Control signal as a refusal, without asking', async () => {
    vi.stubGlobal('navigator', { ...navigator, globalPrivacyControl: true });
    stubConsent(WITH_CATEGORY);
    renderBanner();

    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('false'));
    // The visitor already answered, in their browser settings. Putting a banner in front of them anyway
    // would be asking a question that has been answered.
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  it('survives a payload that is missing its arrays', async () => {
    // Consent sits at the root of the shell: a partial payload must not take the page down.
    stubConsent({});
    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('false'));
  });
});
