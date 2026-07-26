// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { buildCtx } from '../plugins/buildCtx';
import { ConsentBanner } from './ConsentBanner';
import { ConsentProvider, useConsent } from './ConsentContext';

const WITH_CATEGORY = {
  categories: [{ category: 'analytics', pluginIds: ['stats'], known: true }],
  sources: [{ source: 'plausible.example', pluginId: 'stats' }],
  privacySlug: 'privacy',
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

describe('Consent (M5 E5d)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('stays banner-free when no plugin declares a category', async () => {
    stubConsent({ categories: [], sources: [], privacySlug: null });
    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toBeInTheDocument());
    expect(screen.queryByText('Third-party content')).not.toBeInTheDocument();
  });

  it('asks once a plugin declares one, and denies until the visitor agrees', async () => {
    stubConsent(WITH_CATEGORY);
    renderBanner();

    expect(await screen.findByText('Third-party content')).toBeInTheDocument();
    // Named plugin and declared host are shown — the notice comes from the declaration, not from prose.
    expect(screen.getByText(/stats/)).toBeInTheDocument();
    expect(screen.getByText(/plausible\.example/)).toBeInTheDocument();
    // Default deny while undecided.
    expect(screen.getByTestId('granted')).toHaveTextContent('false');

    fireEvent.click(screen.getByText('Allow all'));
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('true'));
    expect(screen.queryByText('Third-party content')).not.toBeInTheDocument();
  });

  it('persists a refusal, so the banner does not ask again', async () => {
    stubConsent(WITH_CATEGORY);
    const first = renderBanner();
    expect(await screen.findByText('Third-party content')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Allow none'));
    await waitFor(() => expect(screen.queryByText('Third-party content')).not.toBeInTheDocument());
    first.unmount();

    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('false'));
    expect(screen.queryByText('Third-party content')).not.toBeInTheDocument();
  });

  it('survives a payload that is missing its arrays', async () => {
    // Consent sits at the root of the shell: a partial payload must not take the page down.
    stubConsent({});
    renderBanner();
    await waitFor(() => expect(screen.getByTestId('granted')).toHaveTextContent('false'));
  });
});
