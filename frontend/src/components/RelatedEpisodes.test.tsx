// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import '../i18n';
import type { EpisodeSummary } from '../api/types';
import { RelatedEpisodes } from './RelatedEpisodes';

const summary = (id: string, title: string): EpisodeSummary =>
  ({
    id,
    slug: id,
    feedId: 'feed-1',
    season: 1,
    episodeNo: 2,
    status: 'PUBLISHED',
    access: 'PUBLIC',
    accessTierRef: null,
    title,
    subtitle: null,
    author: null,
    imageUrl: null,
    excerpt: null,
    publishedAt: '2026-03-04T10:00:00Z',
    durationSeconds: 1200,
    hasAudio: true,
  }) as EpisodeSummary;

function renderWidget(episodes: EpisodeSummary[] | null, error: unknown = null) {
  return render(
    <MemoryRouter>
      <RelatedEpisodes episodes={episodes} error={error} />
    </MemoryRouter>,
  );
}

describe('RelatedEpisodes', () => {
  it('lists what the host suggested, linking each to its episode', async () => {
    renderWidget([summary('deep-water', 'Deep Water')]);

    const link = await screen.findByRole('link', { name: /Deep Water/ });
    expect(link).toHaveAttribute('href', '/episodes/deep-water');
    expect(screen.getByRole('heading', { name: 'Related episodes' })).toBeInTheDocument();
  });

  it('renders nothing at all when there is nothing to suggest', () => {
    // A heading over an empty list reads as breakage; a site with one episode simply has no related ones.
    expect(renderWidget([]).container).toBeEmptyDOMElement();
  });

  it('stays silent when the request failed', () => {
    // This is a sidebar extra. A failed suggestion list is not worth an error on someone's episode page.
    expect(renderWidget(null, new Error('boom')).container).toBeEmptyDOMElement();
  });
});
