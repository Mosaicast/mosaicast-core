// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { LoginErrorBanner } from '../components/LoginErrorBanner';
import { FeedbackProvider } from './FeedbackContext';
import { FeedbackLink } from './ReportThis';

const state = vi.hoisted(() => ({
  meta: { name: 'Mosaicast', version: '0.11.0', devLoginEnabled: false, feedbackMode: 'everyone' } as Record<
    string,
    unknown
  >,
  user: null as null | { id: string; displayName: string; role: string },
}));

vi.mock('../api/MetaContext', () => ({ useMeta: () => state.meta, useRefreshMeta: () => () => {} }));
vi.mock('../auth/UserContext', () => ({ useUser: () => ({ user: state.user, refresh: () => {} }) }));

type Call = { url: string; method: string; body?: Record<string, unknown> };

/** Stubs the two feedback endpoints; `refusal` makes the POST answer with that problem slug. */
function stubFetch(refusal?: { status: number; slug: string }) {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body ? JSON.parse(init.body as string) : undefined });
      if (method === 'POST' && refusal) {
        return Promise.resolve({
          ok: false,
          status: refusal.status,
          statusText: '',
          headers: { get: () => null },
          json: () => Promise.resolve({ type: `https://mosaicast.dev/problems/${refusal.slug}` }),
        });
      }
      const body = url.endsWith('/challenge') ? { challenge: 'c.sig', bits: 8 } : undefined;
      return Promise.resolve({
        ok: true,
        status: body ? 200 : 202,
        headers: { get: () => null },
        text: () => Promise.resolve(body ? JSON.stringify(body) : ''),
      });
    }),
  );
  return calls;
}

const solve = vi.fn(() => Promise.resolve('324'));

function renderShell(path = '/episodes/one', extra?: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <FeedbackProvider solve={solve}>
        <FeedbackLink />
        {extra}
      </FeedbackProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  solve.mockClear();
  state.meta = { ...state.meta, feedbackMode: 'everyone', feedbackUrl: null };
  state.user = null;
});

describe('feedback', () => {
  it('sends an anonymous report with a solved proof of work and the page it came from', async () => {
    const calls = stubFetch();
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    fireEvent.change(screen.getByLabelText('Your message'), { target: { value: 'The player stutters' } });
    fireEvent.change(screen.getByLabelText('How to reach you (optional)'), { target: { value: 'me@example.org' } });
    expect(screen.getByText('Page: /episodes/one')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    await screen.findByText('Thanks! Your feedback is on its way.');
    expect(solve).toHaveBeenCalledWith('c.sig', 8);
    const post = calls.find((c) => c.method === 'POST');
    expect(post?.url).toBe('/api/feedback');
    expect(post?.body).toMatchObject({
      kind: 'idea',
      message: 'The player stutters',
      contact: 'me@example.org',
      challenge: 'c.sig',
      solution: '324',
      website: '',
      context: { route: '/episodes/one' },
    });
  });

  it('asks a signed-in sender for no contact and no proof of work', async () => {
    state.user = { id: 'u1', displayName: 'Fan', role: 'fan' };
    const calls = stubFetch();
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    expect(screen.queryByLabelText('How to reach you (optional)')).toBeNull();
    fireEvent.change(screen.getByLabelText('Your message'), { target: { value: 'hello' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    await screen.findByText('Thanks! Your feedback is on its way.');
    expect(solve).not.toHaveBeenCalled();
    expect(calls.some((c) => c.url.endsWith('/challenge'))).toBe(false);
  });

  it('holds an anonymous message with too many links before it is sent', () => {
    stubFetch();
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    fireEvent.change(screen.getByLabelText('Your message'), {
      target: { value: 'https://a.example https://b.example https://c.example' },
    });
    expect(screen.getByRole('alert').textContent).toBe('Use at most 2 links, or sign in.');
    expect((screen.getByRole('button', { name: 'Send' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('says what to do about a refusal', async () => {
    stubFetch({ status: 503, slug: 'feedback-busy' });
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    fireEvent.change(screen.getByLabelText('Your message'), { target: { value: 'hello' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toBe(
        'Too many reports are waiting. Sign in, or try again later.',
      ),
    );
  });

  it('points elsewhere in external mode and offers nothing when off', () => {
    state.meta = { ...state.meta, feedbackMode: 'external', feedbackUrl: 'mailto:team@example.org' };
    const { unmount } = renderShell();
    expect(screen.getByRole('link', { name: 'Send feedback' }).getAttribute('href')).toBe('mailto:team@example.org');
    unmount();
    state.meta = { ...state.meta, feedbackMode: 'off' };
    renderShell();
    expect(screen.queryByText('Send feedback')).toBeNull();
  });

  it('offers the form to a signed-out visitor only when the site takes anonymous reports', () => {
    state.meta = { ...state.meta, feedbackMode: 'signed-in' };
    renderShell();
    expect(screen.queryByText('Send feedback')).toBeNull();
  });

  it('lets a failed sign-in be reported, with its own wording and code', async () => {
    const calls = stubFetch();
    renderShell('/?login_error=signups_closed', <LoginErrorBanner />);
    // Its own wording, which the banner used to replace with the generic failure.
    expect(screen.getByRole('alert').textContent).toContain("You can't create an account yet. Sign-ups open with the site.");
    expect(screen.getByRole('alert').textContent).not.toContain("Login failed. Try again.");
    fireEvent.click(screen.getByRole('button', { name: 'Report this' }));
    expect(screen.getByText('What went wrong: signing in failed')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Your message'), { target: { value: 'cannot join' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    await screen.findByText('Thanks! Your feedback is on its way.');
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      kind: 'problem',
      context: { error: 'signups_closed' },
    });
  });
});
