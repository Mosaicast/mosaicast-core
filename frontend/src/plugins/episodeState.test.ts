// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { pluginEpisodeState } from './episodeState';

describe('pluginEpisodeState', () => {
  it('takes the phase the host computed, in the SDK spelling', () => {
    expect(pluginEpisodeState('PLANNED', 'PLANNED')).toEqual({ status: 'PLANNED', phase: 'planned' });
    expect(pluginEpisodeState('PLANNED', 'UPCOMING', '2026-11-01T18:00:00Z')).toEqual({
      status: 'PLANNED',
      phase: 'upcoming',
      announceAt: '2026-11-01T18:00:00Z',
    });
    expect(pluginEpisodeState('PUBLISHED', 'RELEASED')).toEqual({ status: 'PUBLISHED', phase: 'released' });
  });

  it('derives the phase from the status where a card has none', () => {
    expect(pluginEpisodeState('PUBLISHED')?.phase).toBe('released');
    expect(pluginEpisodeState('WITHDRAWN')?.phase).toBe('withdrawn');
    // Public lists never carry a quiet plan, so a planned card is an announced one.
    expect(pluginEpisodeState('PLANNED')?.phase).toBe('upcoming');
  });

  it('keeps the announcement only while the episode is planned', () => {
    expect(pluginEpisodeState('PUBLISHED', 'RELEASED', '2026-11-01T18:00:00Z')).toEqual({
      status: 'PUBLISHED',
      phase: 'released',
    });
  });

  it('gives nothing for a status it does not know', () => {
    expect(pluginEpisodeState(undefined)).toBeUndefined();
    expect(pluginEpisodeState('DRAFT')).toBeUndefined();
  });
});
